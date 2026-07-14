package me.darknet.dex.convert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Removes a narrowly proven local materialization pair from a staged method.
 * The producer value is already on the operand stack when the store is
 * removed, so the adjacent load continues the original expression.
 */
final class JvmLocalMaterializationCleanup {
    private JvmLocalMaterializationCleanup() {
    }

    static int removeDeadAdjacentPairs(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int index = 0; index + 1 < method.instructions.size(); index++) {
                AbstractInsnNode storeNode = method.instructions.get(index);
                int loadIndex = nextMaterializationInstruction(method, index + 1);
                if (loadIndex < 0) continue;
                AbstractInsnNode loadNode = method.instructions.get(loadIndex);
                if (!(storeNode instanceof VarInsnNode store)
                        || !(loadNode instanceof VarInsnNode load)
                        || store.var != load.var
                        || loadOpcode(store.getOpcode()) != load.getOpcode()) continue;
                if (!sameProtectedProfile(method, storeNode, loadNode)) continue;
                if (isProtected(method, storeNode) && handlerUsesLocal(method, storeNode, load.var)) continue;
                if (hasLaterLiveUse(method, loadIndex + 1, load.var)) continue;
                method.instructions.remove(loadNode);
                method.instructions.remove(storeNode);
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Re-emits a small non-void invocation slice at its one consumer.  This is
     * intentionally narrower than general expression fusion: only a no-arg
     * receiver call (with an ALOAD/GETFIELD receiver) or a static call whose
     * sole argument is a constant is supported, and the consumer must be the
     * first local use on the same protected-range profile.  A static receiver
     * may remain below arguments that are computed by a straight-line stack
     * proof; branches, handlers, protected boundaries, and category conflicts
     * remain hard boundaries.
     */
    static int removeOneUseInvokeCopies(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int storeIndex = 0; storeIndex < method.instructions.size(); storeIndex++) {
                AbstractInsnNode storeNode = method.instructions.get(storeIndex);
                if (!(storeNode instanceof VarInsnNode store) || !isStore(store.getOpcode())) continue;
                int producerIndex = previousExecutableIndex(method, storeIndex - 1);
                if (producerIndex < 0 || !(method.instructions.get(producerIndex) instanceof MethodInsnNode producer)
                        || producer.desc.endsWith(")V")) continue;
                List<AbstractInsnNode> slice = invocationProducerSlice(method, producerIndex, producer);
                if (slice.isEmpty()) continue;
                int consumerIndex = firstCopyUse(method, storeIndex + 1, store.var);
                if (consumerIndex < 0 || !(method.instructions.get(consumerIndex) instanceof VarInsnNode consumer)
                        || consumer.getOpcode() != loadOpcode(store.getOpcode())) continue;
                if ((hasAnyLocalRead(method, consumerIndex + 1, store.var)
                        && hasReadBeforeReassignment(method, consumerIndex + 1, store.var))
                        || !sameProtectedProfile(method, slice.getFirst(), consumer)
                        || (isProtected(method, storeNode) && handlerUsesLocal(method, storeNode, store.var))) continue;

                boolean invalidIntervening = false;
                for (int index = storeIndex + 1; index < consumerIndex; index++) {
                    AbstractInsnNode instruction = method.instructions.get(index);
                    if (instruction.getType() == AbstractInsnNode.LINE
                            || instruction.getType() == AbstractInsnNode.FRAME) continue;
                    if (instruction instanceof VarInsnNode variable && isLoad(variable.getOpcode())) continue;
                    if (isConstantInstruction(instruction)) continue;
                    invalidIntervening = true;
                    break;
                }
                if (invalidIntervening) {
                    if (producer.getOpcode() == Opcodes.INVOKESTATIC
                            && stackReceiverIntervening(method, storeIndex + 1, consumerIndex, consumerIndex)
                            && !hasAnyLocalRead(method, consumerIndex + 1, store.var)
                            && sameProtectedProfile(method, producer, consumer)) {
                        // The invocation result is the receiver of the next
                        // call.  Keeping it below independent argument loads
                        // preserves the original evaluation order and avoids
                        // materializing a receiver-only temporary.
                        method.instructions.remove(consumer);
                        method.instructions.remove(storeNode);
                        removed++;
                        changed = true;
                        break;
                    }
                    continue;
                }

                for (AbstractInsnNode instruction : slice)
                    method.instructions.insertBefore(consumer, instruction.clone(new HashMap<>()));
                method.instructions.remove(consumer);
                method.instructions.remove(storeNode);
                for (AbstractInsnNode instruction : slice)
                    method.instructions.remove(instruction);
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Re-emits a constructor-backed value at its single invoke argument.  The
     * local-first emitter normally has to spell this as {@code NEW; ASTORE;
     * ALOAD; <init>; ...; ALOAD; invoke}.  When the value has one consumer,
     * the constructor can instead be emitted as {@code NEW; DUP; args;
     * <init>; invoke}, which gives decompilers the original nested expression
     * without carrying an object local through the surrounding handshake.
     *
     * <p>The proof is deliberately narrow: the constructor and consumer must
     * be on one protected profile, the only instructions between construction
     * and consumption may be metadata, transparent fall-through labels, or
     * local loads, and the consuming call must have one reference argument.
     * No frame, branch, protected/handler label, handler use, or later local
     * access is crossed.</p>
     */
    static int removeOneUseConstructorCopies(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int allocationIndex = 0; allocationIndex < method.instructions.size(); allocationIndex++) {
                AbstractInsnNode allocationNode = method.instructions.get(allocationIndex);
                if (!(allocationNode instanceof TypeInsnNode allocation)
                        || allocation.getOpcode() != Opcodes.NEW) continue;
                int storeIndex = nextExecutableIndex(method, allocationIndex + 1);
                if (storeIndex < 0 || !(method.instructions.get(storeIndex) instanceof VarInsnNode store)
                        || store.getOpcode() != Opcodes.ASTORE) continue;
                AbstractInsnNode storeInstruction = method.instructions.get(storeIndex);
                if (isProtected(method, storeInstruction)
                        && handlerUsesLocal(method, storeInstruction, store.var)) continue;

                int receiverIndex = nextExecutableIndex(method, storeIndex + 1);
                if (receiverIndex < 0 || !(method.instructions.get(receiverIndex) instanceof VarInsnNode receiver)
                        || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != store.var) continue;

                int constructorIndex = -1;
                boolean invalid = false;
                for (int cursor = receiverIndex + 1;
                     cursor < Math.min(method.instructions.size(), receiverIndex + 48); cursor++) {
                    AbstractInsnNode instruction = method.instructions.get(cursor);
                    if (instruction.getType() == AbstractInsnNode.LINE) continue;
                    if (instruction.getType() == AbstractInsnNode.FRAME
                            || instruction.getType() == AbstractInsnNode.LABEL
                            || instruction instanceof JumpInsnNode
                            || instruction instanceof TableSwitchInsnNode
                            || instruction instanceof LookupSwitchInsnNode
                            || isTerminal(instruction)) {
                        invalid = true;
                        break;
                    }
                    if (instruction instanceof VarInsnNode variable && isStore(variable.getOpcode())) {
                        invalid = true;
                        break;
                    }
                    if (instruction instanceof MethodInsnNode invoke
                            && invoke.getOpcode() == Opcodes.INVOKESPECIAL
                            && "<init>".equals(invoke.name)
                            && allocation.desc.equals(invoke.owner)) {
                        constructorIndex = cursor;
                        break;
                    }
                }
                if (invalid || constructorIndex < 0) continue;

                int consumerIndex = firstCopyUse(method, constructorIndex + 1, store.var);
                if (consumerIndex < 0 || !(method.instructions.get(consumerIndex) instanceof VarInsnNode consumer)
                        || consumer.getOpcode() != Opcodes.ALOAD
                        || hasAnyLocalAccess(method, consumerIndex + 1, store.var)
                        || handlerUsesLocal(method, storeInstruction, store.var)) continue;

                int invokeIndex = nextExecutableIndex(method, consumerIndex + 1);
                if (invokeIndex < 0 || !(method.instructions.get(invokeIndex) instanceof MethodInsnNode invoke)
                        || org.objectweb.asm.Type.getArgumentTypes(invoke.desc).length != 1
                        || org.objectweb.asm.Type.getArgumentTypes(invoke.desc)[0].getSort() != org.objectweb.asm.Type.OBJECT
                        && org.objectweb.asm.Type.getArgumentTypes(invoke.desc)[0].getSort() != org.objectweb.asm.Type.ARRAY) continue;

                // Only cross pure local loads.  In particular, a field read,
                // call, branch, or frame between construction and consumption
                // could observe a different exception/evaluation order.
                if (!onlyLocalLoadsBetween(method, constructorIndex + 1, consumerIndex)) {
                    continue;
                }
                boolean unsupportedMetadata = false;
                for (int cursor = allocationIndex; cursor <= constructorIndex; cursor++) {
                    AbstractInsnNode instruction = method.instructions.get(cursor);
                    if (instruction.getType() == AbstractInsnNode.FRAME
                            || instruction instanceof LabelNode label
                            && !isUnreferencedFallthroughLabel(method, label, cursor)) {
                        unsupportedMetadata = true;
                        break;
                    }
                }
                if (unsupportedMetadata) continue;

                List<AbstractInsnNode> expression = new ArrayList<>();
                for (int cursor = allocationIndex; cursor <= constructorIndex; cursor++) {
                    AbstractInsnNode instruction = method.instructions.get(cursor);
                    if (instruction.getType() == AbstractInsnNode.LINE
                            || instruction.getType() == AbstractInsnNode.FRAME
                            || instruction.getType() == AbstractInsnNode.LABEL) continue;
                    if (instruction == store || instruction == receiver) continue;
                    expression.add(instruction.clone(new HashMap<>()));
                    if (instruction == allocationNode)
                        expression.add(new InsnNode(Opcodes.DUP));
                }

                for (int cursor = constructorIndex; cursor >= allocationIndex; cursor--) {
                    AbstractInsnNode instruction = method.instructions.get(cursor);
                    if (instruction.getType() == AbstractInsnNode.LINE
                            || instruction.getType() == AbstractInsnNode.FRAME) continue;
                    method.instructions.remove(instruction);
                }
                for (AbstractInsnNode instruction : expression)
                    method.instructions.insertBefore(consumer, instruction);
                method.instructions.remove(consumer);
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Re-emits a constructor-backed exception directly at {@code ATHROW}.
     * Local-first lowering normally produces {@code NEW; ASTORE; ALOAD;
     * arguments; <init>; ALOAD; ATHROW}.  When the exception local is not
     * observable from any handler, the initialized object can remain on the
     * operand stack for the terminal throw.  This is the bytecode shape
     * produced by a source-level {@code throw new Exception(...)} and removes
     * a temporary without moving an effect across a control-flow boundary.
     */
    static int removeOneUseConstructorThrows(MethodNode method) {
        for (int allocationIndex = 0; allocationIndex < method.instructions.size(); allocationIndex++) {
            AbstractInsnNode allocationNode = method.instructions.get(allocationIndex);
            if (!(allocationNode instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW) continue;
            int storeIndex = nextExecutableIndex(method, allocationIndex + 1);
            if (storeIndex < 0 || !(method.instructions.get(storeIndex) instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ASTORE
                    || isProtected(method, store) && handlerUsesLocal(method, store, store.var)) continue;

            int receiverIndex = nextExecutableIndex(method, storeIndex + 1);
            if (receiverIndex < 0 || !(method.instructions.get(receiverIndex) instanceof VarInsnNode receiver)
                    || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != store.var) continue;

            int constructorIndex = -1;
            for (int cursor = receiverIndex + 1;
                 cursor < Math.min(method.instructions.size(), receiverIndex + 24); cursor++) {
                AbstractInsnNode instruction = method.instructions.get(cursor);
                if (instruction.getType() == AbstractInsnNode.LINE) continue;
                if (instruction.getType() == AbstractInsnNode.FRAME
                        || instruction instanceof LabelNode label
                        && !isUnreferencedFallthroughLabel(method, label, cursor)
                        || instruction instanceof JumpInsnNode
                        || instruction instanceof TableSwitchInsnNode
                        || instruction instanceof LookupSwitchInsnNode
                        || instruction instanceof VarInsnNode variable && isStore(variable.getOpcode())
                        || isTerminal(instruction)) break;
                if (instruction instanceof MethodInsnNode invoke
                        && invoke.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(invoke.name)
                        && allocation.desc.equals(invoke.owner)) {
                    constructorIndex = cursor;
                    break;
                }
            }
            if (constructorIndex < 0) continue;

            int thrownLoadIndex = nextExecutableIndex(method, constructorIndex + 1);
            int throwIndex = nextExecutableIndex(method, thrownLoadIndex + 1);
            if (thrownLoadIndex < 0 || throwIndex < 0
                    || !(method.instructions.get(thrownLoadIndex) instanceof VarInsnNode thrownLoad)
                    || thrownLoad.getOpcode() != Opcodes.ALOAD || thrownLoad.var != store.var
                    || method.instructions.get(throwIndex).getOpcode() != Opcodes.ATHROW
                    || !sameProtectedProfile(method, allocationNode, method.instructions.get(throwIndex))) continue;

            boolean hasOtherLocalUse = false;
            for (int cursor = receiverIndex + 1; cursor < thrownLoadIndex; cursor++) {
                AbstractInsnNode instruction = method.instructions.get(cursor);
                if (instruction instanceof VarInsnNode variable && variable.var == store.var) {
                    hasOtherLocalUse = true;
                    break;
                }
            }
            if (hasOtherLocalUse || handlerUsesLocal(method, store, store.var)) continue;

            method.instructions.insertBefore(store, new InsnNode(Opcodes.DUP));
            method.instructions.remove(store);
            method.instructions.remove(receiver);
            method.instructions.remove(thrownLoad);
            return 1;
        }
        return 0;
    }

    /** Re-emits a no/constant-argument static producer as an invoke receiver. */
    static int removeOneUseStaticReceiverCopies(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int storeIndex = 0; storeIndex < method.instructions.size(); storeIndex++) {
                AbstractInsnNode storeNode = method.instructions.get(storeIndex);
                if (!(storeNode instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;
                int producerIndex = previousExecutableIndex(method, storeIndex - 1);
                if (producerIndex < 0 || !(method.instructions.get(producerIndex) instanceof MethodInsnNode producer)
                        || producer.getOpcode() != Opcodes.INVOKESTATIC
                        || producer.desc.endsWith(")V")) continue;
                List<AbstractInsnNode> slice = invocationProducerSlice(method, producerIndex, producer);
                if (slice.isEmpty()) continue;
                int consumerIndex = firstCopyUse(method, storeIndex + 1, store.var);
                if (consumerIndex < 0 || !(method.instructions.get(consumerIndex) instanceof VarInsnNode consumer)
                        || consumer.getOpcode() != Opcodes.ALOAD
                        || hasAnyLocalAccess(method, consumerIndex + 1, store.var)
                        || handlerUsesLocal(method, storeNode, store.var)
                        || !sameProtectedProfile(method, slice.getFirst(), consumer)
                        || !onlyLocalLoadsBetween(method, storeIndex + 1, consumerIndex)) continue;
                int invokeIndex = nextExecutableIndex(method, consumerIndex + 1);
                if (invokeIndex < 0 || !(method.instructions.get(invokeIndex) instanceof MethodInsnNode invoke)
                        || invoke.getOpcode() == Opcodes.INVOKESTATIC
                        || invoke.getOpcode() == Opcodes.INVOKESPECIAL) continue;

                List<AbstractInsnNode> copies = new ArrayList<>();
                for (AbstractInsnNode instruction : slice)
                    copies.add(instruction.clone(new HashMap<>()));
                for (AbstractInsnNode instruction : slice)
                    method.instructions.remove(instruction);
                method.instructions.remove(storeNode);
                for (AbstractInsnNode instruction : copies)
                    method.instructions.insertBefore(consumer, instruction);
                method.instructions.remove(consumer);
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Retargets a catch-all close bridge to an existing equivalent handler
     * entry.  DEX splitting can produce {@code handler: ASTORE x; GOTO body}
     * even when another protected range already targets {@code entry: ASTORE
     * x; body}.  The retarget is valid only when both ranges have the same
     * catch type, the store category/local agree, and the canonical entry's
     * next instruction is exactly the bridge destination.
     */
    static int retargetEquivalentHandlerStoreBridges(MethodNode method) {
        int retargeted = 0;
        boolean changed;
        do {
            changed = false;
            for (TryCatchBlockNode range : new ArrayList<>(method.tryCatchBlocks)) {
                LabelNode bridge = range.handler;
                if (range.type != null || bridge == null || hasStartOrEndBoundary(method, bridge)) continue;
                int bridgeIndex = method.instructions.indexOf(bridge);
                if (bridgeIndex < 0) continue;
                int bridgeStoreIndex = nextExecutableIndex(method, bridgeIndex + 1);
                if (bridgeStoreIndex < 0
                        || !(method.instructions.get(bridgeStoreIndex) instanceof VarInsnNode bridgeStore)
                        || bridgeStore.getOpcode() != Opcodes.ASTORE) continue;
                int gotoIndex = nextExecutableIndex(method, bridgeStoreIndex + 1);
                if (gotoIndex < 0 || !(method.instructions.get(gotoIndex) instanceof JumpInsnNode jump)
                        || jump.getOpcode() != Opcodes.GOTO) continue;

                LabelNode canonical = null;
                for (TryCatchBlockNode candidateRange : method.tryCatchBlocks) {
                    if (candidateRange == range || candidateRange.type != null
                            || !java.util.Objects.equals(candidateRange.type, range.type)) continue;
                    LabelNode candidate = candidateRange.handler;
                    if (candidate == bridge || candidate == null) continue;
                    int candidateIndex = method.instructions.indexOf(candidate);
                    if (candidateIndex < 0) continue;
                    int candidateStoreIndex = nextExecutableIndex(method, candidateIndex + 1);
                    if (candidateStoreIndex < 0
                            || !(method.instructions.get(candidateStoreIndex) instanceof VarInsnNode candidateStore)
                            || candidateStore.getOpcode() != Opcodes.ASTORE
                            || candidateStore.var != bridgeStore.var
                            || candidateStore.getOpcode() != bridgeStore.getOpcode()) continue;
                    int candidateBody = nextInstructionIndex(method, candidateStoreIndex + 1);
                    if (candidateBody >= 0 && method.instructions.get(candidateBody) == jump.label) {
                        canonical = candidate;
                        break;
                    }
                }
                if (canonical == null) continue;
                range.handler = canonical;
                if (hasNoExternalLabelReferences(method, bridge))
                    removeLabelBlock(method, bridge);
                retargeted++;
                changed = true;
                break;
            }
        } while (changed);
        return retargeted;
    }

    /**
     * DEX cleanup lowering can leave the ordinary completion path jumping
     * over the catch handlers to a tail which is also used by the catch path.
     * That is verifier-safe, but it makes CFR model normal completion as a
     * synthetic break.  When the tail is a pure, local-backed cleanup suffix,
     * duplicate it immediately before the handler region.  The normal path
     * then falls through its cleanup and returns before the handlers, while
     * the original tail remains available to the exceptional path.
     *
     * <p>This is deliberately a bytecode-layout proof, not a method-specific
     * rewrite.  The suffix must contain the same-key map-removal cleanup
     * pattern, be outside all protected ranges, have no handler labels, and be
     * reached by both a normal non-handler edge and another edge.  Cloning is
     * limited to the immediate cleanup suffix; no value is moved on the
     * operand stack and no exception-table range is rewritten.</p>
     */
    static int duplicateSharedNormalCleanupTails(MethodNode method) {
        for (int jumpIndex = 0; jumpIndex < method.instructions.size(); jumpIndex++) {
            AbstractInsnNode node = method.instructions.get(jumpIndex);
            if (!(node instanceof JumpInsnNode normalJump) || normalJump.getOpcode() != Opcodes.GOTO)
                continue;
            LabelNode tail = normalJump.label;
            int tailIndex = method.instructions.indexOf(tail);
            if (tailIndex <= jumpIndex || isRangeBoundary(method, tail)
                    || isHandlerLabel(method, tail)) continue;
            if (isProtected(method, normalJump)) continue;

            int sourceLabelIndex = previousLabelIndex(method, jumpIndex - 1);
            if (sourceLabelIndex < 0 || !(method.instructions.get(sourceLabelIndex) instanceof LabelNode sourceLabel)
                    || isHandlerLabel(method, sourceLabel) || isRangeBoundary(method, sourceLabel)) continue;
            int prior = previousExecutableIndex(method, jumpIndex - 1);
            if (!(prior >= 0 && method.instructions.get(prior) instanceof JumpInsnNode conditional)
                    || (conditional.getOpcode() != Opcodes.IFNULL
                    && conditional.getOpcode() != Opcodes.IFNONNULL)) continue;

            int tailEnd = cleanupTailEnd(method, tailIndex);
            if (tailEnd < 0 || !isLocalCleanupTail(method, tailIndex, tailEnd)) continue;
            if (tailIncomingCount(method, tail) < 2) continue;

            // The cloned suffix must not be protected or contain an entry
            // label.  The original suffix remains in place for the handler
            // path, so exception-table labels and priority are untouched.
            boolean invalid = false;
            for (int index = tailIndex; index <= tailEnd; index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                if (instruction instanceof LabelNode label
                        && (isHandlerLabel(method, label) || isRangeBoundary(method, label))) {
                    invalid = true;
                    break;
                }
                if (isProtected(method, instruction)) {
                    invalid = true;
                    break;
                }
            }
            if (invalid) continue;

            Map<LabelNode, LabelNode> labels = new HashMap<>();
            for (int index = tailIndex; index <= tailEnd; index++) {
                if (method.instructions.get(index) instanceof LabelNode label)
                    labels.put(label, new LabelNode());
            }
            List<AbstractInsnNode> copies = new ArrayList<>();
            for (int index = tailIndex; index <= tailEnd; index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                copies.add(instruction.clone(labels));
            }
            int insertionIndex = jumpIndex + 1;
            if (insertionIndex >= method.instructions.size()) continue;
            AbstractInsnNode insertionPoint = method.instructions.get(insertionIndex);
            for (AbstractInsnNode copy : copies)
                method.instructions.insertBefore(insertionPoint, copy);
            method.instructions.remove(normalJump);
            return 1;
        }
        return 0;
    }

    /**
     * Rehomes a pure throw block that an in-range conditional branch targets
     * outside its active resource/catch envelope.  DEX block order often
     * leaves checks such as "wrong frame type" at the end of the method. CFR
     * then sees a branch out of the protected body and cannot reconstruct the
     * enclosing catch/finally.  Inverting the branch and placing the throw
     * sequence on its false fall-through keeps the throw inside every range
     * active at the original branch.
     *
     * <p>The transformation is limited to a single incoming, straight-line
     * throw block. It clones executable instructions only, leaves exception
     * table labels untouched, and removes the old block only after its branch
     * reference has been retargeted.</p>
     */
    static int relocateProtectedThrowBranches(MethodNode method) {
        for (int branchIndex = 0; branchIndex < method.instructions.size(); branchIndex++) {
            AbstractInsnNode instruction = method.instructions.get(branchIndex);
            if (!(instruction instanceof JumpInsnNode branch) || !isConditional(branch.getOpcode())) continue;
            LabelNode oldTarget = branch.label;
            int oldIndex = method.instructions.indexOf(oldTarget);
            if (oldIndex <= branchIndex || isRangeBoundary(method, oldTarget)
                    || isHandlerLabel(method, oldTarget)
                    || labelIncomingCount(method, oldTarget) == 0
                    || !allIncomingThrowBranchesAreProtected(method, oldTarget, oldIndex)) continue;
            if (containingCatchAll(method, branchIndex, oldIndex) == null) continue;

            int throwEnd = straightLineThrowEnd(method, oldIndex);
            if (throwEnd < 0) continue;
            List<AbstractInsnNode> expression = new ArrayList<>();
            for (int index = oldIndex + 1; index <= throwEnd; index++) {
                AbstractInsnNode candidate = method.instructions.get(index);
                if (candidate.getType() == AbstractInsnNode.LABEL
                        || candidate.getType() == AbstractInsnNode.FRAME
                        || candidate.getType() == AbstractInsnNode.LINE) continue;
                expression.add(candidate.clone(new HashMap<>()));
            }

            int fallthroughIndex = branchIndex + 1;
            if (fallthroughIndex >= method.instructions.size()) continue;
            LabelNode bodyTarget;
            AbstractInsnNode fallthrough = method.instructions.get(fallthroughIndex);
            if (fallthrough instanceof LabelNode label) {
                bodyTarget = label;
            } else {
                bodyTarget = new LabelNode();
                method.instructions.insertBefore(fallthrough, bodyTarget);
            }
            LabelNode throwLabel = new LabelNode();
            method.instructions.insertBefore(bodyTarget, throwLabel);
            for (AbstractInsnNode candidate : expression)
                method.instructions.insertBefore(bodyTarget, candidate);
            branch.setOpcode(invertConditional(branch.getOpcode()));
            branch.label = bodyTarget;
            if (hasNoExternalLabelReferences(method, oldTarget))
                removeLabelBlock(method, oldTarget);
            return 1;
        }
        return 0;
    }

    /**
     * Coalesces identical straight-line failure blocks introduced when two
     * short-circuit predicates originally targeted one DEX block.  The first
     * failure block is removed and its preceding conditional is inverted to
     * target the later canonical block.  Thus the true path falls through to
     * the next predicate while every false path shares one log/throw suffix.
     */
    static int mergeEquivalentProtectedThrowBlocks(MethodNode method) {
        List<ThrowBlock> blocks = new ArrayList<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            if (!(method.instructions.get(index) instanceof LabelNode label)
                    || isRangeBoundary(method, label) || isHandlerLabel(method, label)) continue;
            int previous = previousExecutableIndex(method, index - 1);
            if (previous < 0 || !(method.instructions.get(previous) instanceof JumpInsnNode branch)
                    || !isConditional(branch.getOpcode())) continue;
            int end = straightLineThrowEnd(method, index);
            if (end < 0 || hasAnyIncomingEdge(method, label)
                    || isProtected(method, method.instructions.get(index)) == false) continue;
            blocks.add(new ThrowBlock(label, branch, index, end));
        }
        for (int firstIndex = 0; firstIndex < blocks.size(); firstIndex++) {
            ThrowBlock first = blocks.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < blocks.size(); secondIndex++) {
                ThrowBlock second = blocks.get(secondIndex);
                if (first.startIndex() >= second.startIndex()
                        || !sameThrowBlock(method, first, second)
                        || !sameProtectedProfile(method,
                        method.instructions.get(first.startIndex()),
                        method.instructions.get(second.startIndex()))) continue;
                if (first.branch().label == first.label()
                        || second.branch().label == second.label()) continue;
                first.branch().setOpcode(invertConditional(first.branch().getOpcode()));
                first.branch().label = second.label();
                if (hasNoExternalLabelReferences(method, first.label()))
                    removeLabelBlock(method, first.label());
                return 1;
            }
        }
        return 0;
    }

    /**
     * Moves only the target label of a nullable resource-close branch past a
     * protected-range end marker.  The range remains unchanged; the branch
     * still reaches the same empty-stack state, but CFR no longer treats the
     * range boundary itself as a structured {@code break} target.
     */
    static int retargetNullCloseBoundaryBranches(MethodNode method) {
        int changed = 0;
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) snapshot.add(instruction);
        for (AbstractInsnNode instruction : snapshot) {
            if (!(instruction instanceof JumpInsnNode branch)
                    || branch.getOpcode() != Opcodes.IFNONNULL) continue;
            LabelNode boundary = branch.label;
            if (!isRangeEndOnly(method, boundary)) continue;
            int boundaryIndex = method.instructions.indexOf(boundary);
            int closeIndex = previousExecutableIndex(method, boundaryIndex - 1);
            if (closeIndex < 0 || !(method.instructions.get(closeIndex) instanceof MethodInsnNode close)
                    || close.getOpcode() == Opcodes.INVOKESTATIC
                    || !"close".equals(close.name) || !"()V".equals(close.desc)) continue;
            int targetIndex = nextExecutableIndex(method, boundaryIndex + 1);
            if (targetIndex < 0) continue;
            boolean invalid = false;
            for (int index = boundaryIndex + 1; index < targetIndex; index++) {
                AbstractInsnNode between = method.instructions.get(index);
                if (between instanceof LabelNode label && hasAnyIncomingEdge(method, label)) {
                    invalid = true;
                    break;
                }
            }
            if (invalid) continue;
            LabelNode transparent = new LabelNode();
            method.instructions.insertBefore(method.instructions.get(targetIndex), transparent);
            branch.label = transparent;
            changed++;
        }
        return changed;
    }

    /** Removes a range-end GOTO bridge that follows a completed close call. */
    static int removeRangeEndCloseGotoBridges(MethodNode method) {
        int removed = 0;
        for (TryCatchBlockNode range : new ArrayList<>(method.tryCatchBlocks)) {
            LabelNode end = range.end;
            int endIndex = method.instructions.indexOf(end);
            if (endIndex < 0) continue;
            int closeIndex = previousExecutableIndex(method, endIndex - 1);
            if (closeIndex < 0 || !(method.instructions.get(closeIndex) instanceof MethodInsnNode close)
                    || close.getOpcode() == Opcodes.INVOKESTATIC
                    || !"close".equals(close.name) || !"()V".equals(close.desc)) continue;
            // Keep the explicit join emitted by javac for
            // `if (resource != null) resource.close()`.  Removing it leaves
            // the null branch targeting the range boundary directly, which
            // decompilers commonly recover as `break blockN` instead of a
            // structured conditional.
            int receiverIndex = previousExecutableIndex(method, closeIndex - 1);
            if (receiverIndex >= 0 && isLoad(method.instructions.get(receiverIndex).getOpcode())) {
                int guardIndex = previousExecutableIndex(method, receiverIndex - 1);
                if (guardIndex >= 0 && method.instructions.get(guardIndex) instanceof JumpInsnNode guard
                        && (guard.getOpcode() == Opcodes.IFNULL || guard.getOpcode() == Opcodes.IFNONNULL)
                        && guard.label == end) continue;
            }
            int bridgeIndex = nextExecutableIndex(method, endIndex + 1);
            if (bridgeIndex < 0 || !(method.instructions.get(bridgeIndex) instanceof JumpInsnNode bridge)
                    || bridge.getOpcode() != Opcodes.GOTO || isHandlerLabel(method, bridge.label)) continue;
            method.instructions.remove(bridge);
            removed++;
        }
        return removed;
    }

    /**
     * Moves a resource-handler range end before a null-close guard.  The DEX
     * block order can otherwise leave the end label between {@code IFNULL}
     * and {@code close()}, which makes a decompiler render the guard as a
     * break out of a synthetic block.  The enclosing catch/finally ranges are
     * deliberately left untouched, so a throwing close remains observable by
     * the outer failure path.
     */
    static int normalizeNullCloseRangeEnds(MethodNode method) {
        int moved = 0;
        for (TryCatchBlockNode range : new ArrayList<>(method.tryCatchBlocks)) {
            LabelNode end = range.end;
            if (end == null || !isRangeEndOnly(method, end)) continue;
            int endIndex = method.instructions.indexOf(end);
            int branchIndex = previousExecutableIndex(method, endIndex - 1);
            if (branchIndex < 0 || !(method.instructions.get(branchIndex) instanceof JumpInsnNode branch)
                    || (branch.getOpcode() != Opcodes.IFNULL && branch.getOpcode() != Opcodes.IFNONNULL)) continue;
            int closeIndex = nextExecutableIndex(method, endIndex + 1);
            if (closeIndex >= 0 && method.instructions.get(closeIndex) instanceof VarInsnNode load
                    && isLoad(load.getOpcode()))
                closeIndex = nextExecutableIndex(method, closeIndex + 1);
            if (closeIndex < 0 || !(method.instructions.get(closeIndex) instanceof MethodInsnNode close)
                    || close.getOpcode() == Opcodes.INVOKESTATIC
                    || !"close".equals(close.name) || !"()V".equals(close.desc)) continue;

            int valueIndex = previousExecutableIndex(method, branchIndex - 1);
            if (valueIndex < 0 || isControlFlowBoundary(method, valueIndex, method.instructions.get(valueIndex)))
                continue;
            List<Integer> labelsAtValue = labelsAt(method, valueIndex);
            if (!labelsAtValue.isEmpty()) {
                LabelNode guardBoundary = (LabelNode) method.instructions.get(labelsAtValue.getFirst());
                TryCatchBlockNode closeRange = null;
                for (TryCatchBlockNode other : method.tryCatchBlocks) {
                    if (other == range || other.start != guardBoundary
                            || !java.util.Objects.equals(other.type, range.type)) continue;
                    int otherEnd = method.instructions.indexOf(other.end);
                    if (otherEnd > endIndex && otherEnd > closeIndex) {
                        closeRange = other;
                        break;
                    }
                }
                if (closeRange != null) {
                    // Exchange the two logical boundaries without moving a
                    // label.  The body range ends before the null guard and
                    // the close range begins after it, matching javac's
                    // resource layout while preserving both frame labels.
                    range.end = guardBoundary;
                    closeRange.start = end;
                    moved++;
                    continue;
                }
            }
            // The boundary may also be the start of the following close
            // range.  Split the label in that case: moving the shared label
            // would silently move the close range's start as well and leave
            // its handler with a different frame state.
            boolean sharedWithAnotherRange = false;
            for (TryCatchBlockNode other : method.tryCatchBlocks) {
                if (other == range) continue;
                if (other.start == end || other.handler == end) {
                    sharedWithAnotherRange = true;
                    break;
                }
                if (other.end == end && !java.util.Objects.equals(other.type, range.type))
                    sharedWithAnotherRange = true;
            }

            if (sharedWithAnotherRange) {
                LabelNode splitEnd = new LabelNode();
                method.instructions.insertBefore(method.instructions.get(rangeBoundaryInsertionIndex(method, valueIndex)), splitEnd);
                range.end = splitEnd;
            } else {
                method.instructions.remove(end);
                method.instructions.insertBefore(method.instructions.get(rangeBoundaryInsertionIndex(method, valueIndex)), end);
            }
            moved++;
        }
        return moved;
    }

    /**
     * Gives a null-close guard a non-boundary target.  CFR treats a branch to
     * a try-range start/end as a structured break even when the target is
     * simply the instruction after {@code close()}.  A transparent label and
     * NOP keep the branch target separate from the exception-table label while
     * preserving the exact protected instructions.
     */
    static int decoupleCloseGuardTargets(MethodNode method) {
        int changed = 0;
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) snapshot.add(instruction);
        for (AbstractInsnNode instruction : snapshot) {
            if (!(instruction instanceof JumpInsnNode branch)
                    || (branch.getOpcode() != Opcodes.IFNULL && branch.getOpcode() != Opcodes.IFNONNULL)) continue;
            LabelNode target = branch.label;
            int targetIndex = method.instructions.indexOf(target);
            if (!hasRangeBoundaryAtPosition(method, targetIndex)
                    || hasHandlerAtPosition(method, targetIndex)) continue;
            int closeIndex = previousExecutableIndex(method, targetIndex - 1);
            if (closeIndex < 0 || !(method.instructions.get(closeIndex) instanceof MethodInsnNode close)
                    || close.getOpcode() == Opcodes.INVOKESTATIC
                    || !"close".equals(close.name) || !"()V".equals(close.desc)) continue;
            boolean hasExecutableBetween = false;
            for (int index = closeIndex + 1; index < targetIndex; index++) {
                AbstractInsnNode between = method.instructions.get(index);
                if (between.getType() != AbstractInsnNode.LABEL
                        && between.getType() != AbstractInsnNode.FRAME
                        && between.getType() != AbstractInsnNode.LINE) {
                    hasExecutableBetween = true;
                    break;
                }
            }
            if (hasExecutableBetween) continue;
            LabelNode transparent = new LabelNode();
            method.instructions.insertBefore(target, transparent);
            method.instructions.insertBefore(target, new InsnNode(Opcodes.NOP));
            branch.label = transparent;
            changed++;
        }
        return changed;
    }

    /**
     * Shapes {@code if (resource == null) skip; resource.close();} as the
     * compiler's positive conditional close form.  The explicit skip edge is
     * easier for decompilers to structure and does not move the close across
     * a protected boundary.
     */
    static int shapeCloseGuardConditionals(MethodNode method) {
        int changed = 0;
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) snapshot.add(instruction);
        for (AbstractInsnNode instruction : snapshot) {
            if (!(instruction instanceof JumpInsnNode branch)
                    || (branch.getOpcode() != Opcodes.IFNULL && branch.getOpcode() != Opcodes.IFNONNULL)) continue;
            LabelNode afterClose = branch.label;
            int branchIndex = method.instructions.indexOf(branch);
            int targetIndex = method.instructions.indexOf(afterClose);
            if (targetIndex <= branchIndex) continue;
            int receiverIndex = nextExecutableIndex(method, branchIndex + 1);
            int closeIndex = nextExecutableIndex(method, receiverIndex + 1);
            if (receiverIndex < 0 || closeIndex < 0
                    || !(method.instructions.get(closeIndex) instanceof MethodInsnNode close)
                    || close.getOpcode() == Opcodes.INVOKESTATIC
                    || !"close".equals(close.name) || !"()V".equals(close.desc)) continue;
            boolean targetImmediatelyFollowsClose = true;
            for (int index = closeIndex + 1; index < targetIndex; index++) {
                AbstractInsnNode between = method.instructions.get(index);
                if (between.getType() != AbstractInsnNode.LABEL
                        && between.getType() != AbstractInsnNode.FRAME
                        && between.getType() != AbstractInsnNode.LINE) {
                    targetImmediatelyFollowsClose = false;
                    break;
                }
            }
            if (!targetImmediatelyFollowsClose || isHandlerLabel(method, afterClose)) continue;

            // The fall-through path already evaluates and closes the
            // resource.  Only the condition is inverted: IFNONNULL skips the
            // close, while IFNULL skips it.  Reusing the existing target
            // preserves frames and protected-range boundaries and avoids a
            // synthetic trampoline that decompilers render as a break block.
            branch.setOpcode(Opcodes.IFNULL);
            changed++;
        }
        return changed;
    }

    /**
     * Restores the explicit join used by javac after a nullable resource
     * close.  The close handler range must end before that join; the enclosing
     * resource range continues through it.  Keeping those two boundaries
     * distinct gives decompilers a normal conditional instead of a break out
     * of the enclosing synthetic block.
     */
    static int restoreNullCloseJoinGotos(MethodNode method) {
        int changed = 0;
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) snapshot.add(instruction);
        for (AbstractInsnNode instruction : snapshot) {
            if (!(instruction instanceof JumpInsnNode guard) || guard.getOpcode() != Opcodes.IFNULL)
                continue;
            int guardIndex = method.instructions.indexOf(guard);
            int receiverIndex = nextExecutableIndex(method, guardIndex + 1);
            int closeIndex = nextExecutableIndex(method, receiverIndex + 1);
            if (receiverIndex < 0 || closeIndex < 0
                    || !(method.instructions.get(closeIndex) instanceof MethodInsnNode close)
                    || close.getOpcode() == Opcodes.INVOKESTATIC
                    || !"close".equals(close.name) || !"()V".equals(close.desc)) continue;
            int targetIndex = method.instructions.indexOf(guard.label);
            if (targetIndex <= closeIndex) continue;
            boolean onlyMetadata = true;
            for (int index = closeIndex + 1; index < targetIndex; index++) {
                AbstractInsnNode between = method.instructions.get(index);
                if (between.getType() != AbstractInsnNode.LABEL
                        && between.getType() != AbstractInsnNode.FRAME
                        && between.getType() != AbstractInsnNode.LINE) {
                    onlyMetadata = false;
                    break;
                }
            }
            if (!onlyMetadata || isHandlerLabel(method, guard.label)) continue;

            boolean alreadyJoined = false;
            int next = nextExecutableIndex(method, closeIndex + 1);
            if (next >= 0 && method.instructions.get(next) instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO) alreadyJoined = true;
            if (alreadyJoined) continue;

            // Earlier range normalization can leave the enclosing body and
            // finally ranges ending at the same pre-join boundary as the
            // socket-close range.  Extend only the ranges that started before
            // the guard; the close-specific range is split below.
            for (TryCatchBlockNode range : method.tryCatchBlocks) {
                int start = method.instructions.indexOf(range.start);
                int end = method.instructions.indexOf(range.end);
                if (start <= guardIndex && end >= closeIndex && end < targetIndex)
                    range.end = guard.label;
            }
            List<TryCatchBlockNode> enclosingRanges = new ArrayList<>();
            for (TryCatchBlockNode range : method.tryCatchBlocks) {
                if (range.end == guard.label
                        && method.instructions.indexOf(range.start) <= guardIndex)
                    enclosingRanges.add(range);
            }
            LabelNode closeEnd = new LabelNode();
            method.instructions.insertBefore(guard.label, closeEnd);
            method.instructions.insertBefore(guard.label,
                    new JumpInsnNode(Opcodes.GOTO, guard.label));
            int closeStart = previousExecutableIndex(method, closeIndex - 1);
            for (TryCatchBlockNode range : method.tryCatchBlocks) {
                if (range.end != guard.label) continue;
                int start = method.instructions.indexOf(range.start);
                if (start > guardIndex && start <= closeIndex && start >= closeStart)
                    range.end = closeEnd;
            }
            // Keep the enclosing body/finally ranges through the join.  The
            // close range ends at closeEnd; its enclosing ranges end at the
            // original target after the GOTO.
            for (TryCatchBlockNode range : enclosingRanges) range.end = guard.label;
            for (TryCatchBlockNode range : method.tryCatchBlocks) {
                if (range.end == closeEnd && method.instructions.indexOf(range.start) <= guardIndex)
                    range.end = guard.label;
            }
            // Be tolerant of label coalescing performed by ASM when the
            // method is staged through a ClassWriter: identify the enclosing
            // boundary by its join shape as well as by object identity.
            int boundaryIndex = method.instructions.indexOf(closeEnd);
            int joinIndex = nextExecutableIndex(method, boundaryIndex + 1);
            if (joinIndex >= 0 && method.instructions.get(joinIndex) instanceof JumpInsnNode join
                    && join.getOpcode() == Opcodes.GOTO) {
                for (TryCatchBlockNode range : method.tryCatchBlocks) {
                    if (method.instructions.indexOf(range.start) <= guardIndex
                            && method.instructions.indexOf(range.end) == boundaryIndex)
                        range.end = join.label;
                }
            }
            changed++;
        }
        return changed;
    }

    /**
     * Preserves a local alias for a reference parameter that acts as a
     * protected resource.  Javac gives a try-with-resources resource its own
     * local even when the source parameter is the acquisition value.  Keeping
     * that identity makes the resource lifecycle visible to decompilers while
     * remaining semantically neutral.
     */
    static int preserveProtectedResourceParameterAlias(MethodNode method) {
        if (method.tryCatchBlocks.isEmpty() || method.desc == null) return 0;
        Type[] arguments = Type.getArgumentTypes(method.desc);
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        List<Integer> referenceSlots = new ArrayList<>();
        for (Type argument : arguments) {
            if (argument.getSort() == Type.OBJECT || argument.getSort() == Type.ARRAY)
                referenceSlots.add(slot);
            slot += argument.getSize();
        }

        for (int parameter : referenceSlots) {
            List<AbstractInsnNode> loads = new ArrayList<>();
            boolean hasStore = false;
            boolean closesParameter = false;
            for (int index = 0; index < method.instructions.size(); index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                if (!(instruction instanceof VarInsnNode variable) || variable.var != parameter) continue;
                if (variable.getOpcode() == Opcodes.ASTORE) {
                    hasStore = true;
                    break;
                }
                if (variable.getOpcode() != Opcodes.ALOAD) continue;
                loads.add(instruction);
                int next = nextExecutableIndex(method, index + 1);
                if (next >= 0 && method.instructions.get(next) instanceof MethodInsnNode invoke
                        && invoke.getOpcode() != Opcodes.INVOKESTATIC
                        && "close".equals(invoke.name) && "()V".equals(invoke.desc)
                        && isProtected(method, instruction)) closesParameter = true;
            }
            if (hasStore || !closesParameter || loads.isEmpty()) continue;

            int alias = method.maxLocals;
            AbstractInsnNode firstLoad = loads.getFirst();
            AbstractInsnNode aliasInsertion = firstLoad;
            int previous = previousExecutableIndex(method, method.instructions.indexOf(firstLoad) - 1);
            if (previous >= 0 && method.instructions.get(previous).getOpcode() == Opcodes.DUP) {
                int allocation = previousExecutableIndex(method, previous - 1);
                if (allocation >= 0 && method.instructions.get(allocation).getOpcode() == Opcodes.NEW)
                    aliasInsertion = method.instructions.get(allocation);
            }
            int insertionIndex = method.instructions.indexOf(aliasInsertion);
            for (int index = insertionIndex - 1; index >= 0; index--) {
                int type = method.instructions.get(index).getType();
                if (type == AbstractInsnNode.FRAME || type == AbstractInsnNode.LINE) continue;
                if (method.instructions.get(index) instanceof LabelNode label) aliasInsertion = label;
                break;
            }
            method.instructions.insertBefore(aliasInsertion, new VarInsnNode(Opcodes.ALOAD, parameter));
            method.instructions.insertBefore(aliasInsertion, new VarInsnNode(Opcodes.ASTORE, alias));
            for (AbstractInsnNode load : loads) ((VarInsnNode) load).var = alias;
            method.maxLocals = Math.max(method.maxLocals, alias + 1);
            return 1;
        }
        return 0;
    }

    /**
     * Serializes nested same-type resource handlers along their cleanup
     * handler chain. JVM exception-table order is observable for overlapping
     * ranges, and DEX block construction can otherwise put the outer cleanup
     * range before the output/input close handlers. A handler chain is ordered
     * by handler entry position; ranges sharing an entry are ordered from the
     * later protected start back to the enclosing start. This reproduces the
     * body/close nesting without using method or resource names.
     */
    static int orderNestedExceptionRanges(MethodNode method) {
        if (method.tryCatchBlocks.size() < 2) return 0;
        String type = method.tryCatchBlocks.getFirst().type;
        for (TryCatchBlockNode range : method.tryCatchBlocks)
            if (!java.util.Objects.equals(type, range.type)) return 0;

        List<TryCatchBlockNode> original = new ArrayList<>(method.tryCatchBlocks);
        List<TryCatchBlockNode> sorted = new ArrayList<>(original);
        sorted.sort((left, right) -> {
            int leftHandler = method.instructions.indexOf(left.handler);
            int rightHandler = method.instructions.indexOf(right.handler);
            if (leftHandler != rightHandler) return Integer.compare(leftHandler, rightHandler);
            int leftStart = method.instructions.indexOf(left.start);
            int rightStart = method.instructions.indexOf(right.start);
            if (leftStart != rightStart) return Integer.compare(rightStart, leftStart);
            int leftEnd = method.instructions.indexOf(left.end);
            int rightEnd = method.instructions.indexOf(right.end);
            if (leftEnd != rightEnd) return Integer.compare(leftEnd, rightEnd);
            return Integer.compare(original.indexOf(left), original.indexOf(right));
        });
        if (sorted.equals(original)) return 0;
        method.tryCatchBlocks.clear();
        method.tryCatchBlocks.addAll(sorted);
        return 1;
    }

    /**
     * Extends a catch-to-finally edge over the actual catch body when lowering
     * left only the handler-entry store covered. The end is chosen from a
     * proven local cleanup suffix, so exceptions from logging/publication are
     * transferred to the finally handler while cleanup itself remains outside
     * the catch-body range.
     */
    static int extendCatchToFinallyRanges(MethodNode method) {
        int changed = 0;
        List<TryCatchBlockNode> ranges = new ArrayList<>(method.tryCatchBlocks);
        for (TryCatchBlockNode outer : ranges) {
            if (outer.type != null) continue;
            int outerStart = method.instructions.indexOf(outer.start);
            int outerEnd = method.instructions.indexOf(outer.end);
            if (outerStart < 0 || outerEnd <= outerStart) continue;
            for (TryCatchBlockNode catchRange : ranges) {
                if (catchRange == outer || catchRange.type != null
                        || catchRange.handler != outer.handler) continue;
                int catchStart = method.instructions.indexOf(catchRange.start);
                int catchEnd = method.instructions.indexOf(catchRange.end);
                if (catchStart < 0 || catchEnd <= catchStart || catchStart <= outerEnd
                        || catchEnd - catchStart > 8) continue;

                LabelNode cleanupStart = null;
                int cleanupIndex = -1;
                for (int index = catchEnd; index < method.instructions.size(); index++) {
                    if (!(method.instructions.get(index) instanceof LabelNode label)) continue;
                    int tailEnd = cleanupTailEnd(method, index);
                    if (tailEnd >= index && isLocalCleanupTail(method, index, tailEnd)) {
                        cleanupStart = label;
                        cleanupIndex = index;
                        break;
                    }
                }
                if (cleanupStart == null || cleanupIndex <= catchEnd) continue;
                catchRange.end = cleanupStart;
                changed++;
            }
        }
        return changed;
    }

    /** Removes same-handler ranges fully covered by a larger equivalent range. */
    static int removeRedundantContainedExceptionRanges(MethodNode method) {
        List<TryCatchBlockNode> ranges = new ArrayList<>(method.tryCatchBlocks);
        List<TryCatchBlockNode> removable = new ArrayList<>();
        for (TryCatchBlockNode candidate : ranges) {
            int candidateStart = method.instructions.indexOf(candidate.start);
            int candidateEnd = method.instructions.indexOf(candidate.end);
            if (candidateStart < 0 || candidateEnd <= candidateStart) continue;
            for (TryCatchBlockNode enclosing : ranges) {
                if (candidate == enclosing || !java.util.Objects.equals(candidate.type, enclosing.type)
                        || candidate.handler != enclosing.handler) continue;
                int enclosingStart = method.instructions.indexOf(enclosing.start);
                int enclosingEnd = method.instructions.indexOf(enclosing.end);
                if (enclosingStart < candidateStart && candidateEnd <= enclosingEnd) {
                    removable.add(candidate);
                    break;
                }
            }
        }
        if (removable.isEmpty()) return 0;
        method.tryCatchBlocks.removeIf(removable::contains);
        return removable.size();
    }

    /** Removes an immediately consumed boolean materialization before a branch. */
    static int removeBooleanStoreLoadsBeforeBranches(MethodNode method) {
        List<AbstractInsnNode> snapshot = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) snapshot.add(instruction);
        for (AbstractInsnNode instruction : snapshot) {
            if (!(instruction instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ISTORE) continue;
            int storeIndex = method.instructions.indexOf(store);
            int loadIndex = nextExecutableIndex(method, storeIndex + 1);
            if (loadIndex < 0 || !(method.instructions.get(loadIndex) instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ILOAD || load.var != store.var) continue;
            int branchIndex = nextExecutableIndex(method, loadIndex + 1);
            if (branchIndex < 0 || !(method.instructions.get(branchIndex) instanceof JumpInsnNode branch)
                    || (branch.getOpcode() != Opcodes.IFEQ && branch.getOpcode() != Opcodes.IFNE)) continue;
            if (!sameProtectedProfile(method, store, branch) || handlerUsesLocal(method, store, store.var)) continue;

            boolean metadataOnly = true;
            for (int index = loadIndex + 1; index < branchIndex; index++) {
                AbstractInsnNode between = method.instructions.get(index);
                if (between.getType() != AbstractInsnNode.LABEL
                        && between.getType() != AbstractInsnNode.FRAME
                        && between.getType() != AbstractInsnNode.LINE) {
                    metadataOnly = false;
                    break;
                }
            }
            if (!metadataOnly) continue;
            method.instructions.remove(store);
            method.instructions.remove(load);
            return 1;
        }
        return 0;
    }

    /**
     * Includes a proven parameter-backed resource alias in the broad outer
     * catch/finally envelope. This makes the alias the first resource in the
     * reconstructed try-with-resources sequence while leaving the nested
     * stream-body ranges at their original starts.
     */
    static int widenOuterRangesToResourceAlias(MethodNode method) {
        List<TryCatchBlockNode> ranges = new ArrayList<>(method.tryCatchBlocks);
        for (TryCatchBlockNode range : ranges) {
            if (range.type != null) continue;
            int start = method.instructions.indexOf(range.start);
            if (start < 1) continue;
            int aliasStoreIndex = previousExecutableIndex(method, start - 1);
            if (aliasStoreIndex < 0 || !(method.instructions.get(aliasStoreIndex) instanceof VarInsnNode aliasStore)
                    || aliasStore.getOpcode() != Opcodes.ASTORE) continue;
            int aliasLoadIndex = previousExecutableIndex(method, aliasStoreIndex - 1);
            if (aliasLoadIndex < 0 || !(method.instructions.get(aliasLoadIndex) instanceof VarInsnNode aliasLoad)
                    || aliasLoad.getOpcode() != Opcodes.ALOAD) continue;
            if (!hasCloseUse(method, aliasStore.var)) continue;

            LabelNode originalStart = range.start;
            TryCatchBlockNode broadRange = ranges.stream()
                    .filter(candidate -> candidate.type == null
                            && candidate.start == originalStart)
                    .max(Comparator.comparingInt(candidate -> method.instructions.indexOf(candidate.end)))
                    .orElse(null);
            if (broadRange == null || method.instructions.indexOf(broadRange.end) <= start
                    || range != broadRange) continue;
            LabelNode broadEnd = broadRange.end;
            LabelNode outerStart = new LabelNode();
            method.instructions.insertBefore(method.instructions.get(aliasLoadIndex), outerStart);
            for (TryCatchBlockNode candidate : ranges) {
                if (candidate.type == null
                        && candidate.start == originalStart
                        && candidate.end == broadEnd)
                    candidate.start = outerStart;
            }
            return 1;
        }
        return 0;
    }

    private static boolean hasCloseUse(MethodNode method, int local) {
        for (int index = 0; index < method.instructions.size(); index++) {
            if (!(method.instructions.get(index) instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD || load.var != local) continue;
            int next = nextExecutableIndex(method, index + 1);
            if (next >= 0 && method.instructions.get(next) instanceof MethodInsnNode invoke
                    && invoke.getOpcode() != Opcodes.INVOKESTATIC
                    && "close".equals(invoke.name) && "()V".equals(invoke.desc)) return true;
        }
        return false;
    }

    private static boolean hasRangeBoundaryAtPosition(MethodNode method, int labelIndex) {
        int executable = nextExecutableIndex(method, labelIndex + 1);
        for (int index = 0; index < method.instructions.size(); index++) {
            if (!(method.instructions.get(index) instanceof LabelNode label)
                    || nextExecutableIndex(method, index + 1) != executable) continue;
            if (isRangeBoundary(method, label)) return true;
        }
        return false;
    }

    private static boolean hasHandlerAtPosition(MethodNode method, int labelIndex) {
        int executable = nextExecutableIndex(method, labelIndex + 1);
        for (int index = 0; index < method.instructions.size(); index++) {
            if (!(method.instructions.get(index) instanceof LabelNode label)
                    || nextExecutableIndex(method, index + 1) != executable) continue;
            if (isHandlerLabel(method, label)) return true;
        }
        return false;
    }

    private static boolean isRangeEndOnly(MethodNode method, LabelNode label) {
        boolean end = false;
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            if (range.end == label) end = true;
            if (range.start == label || range.handler == label) return false;
        }
        return end;
    }

    private static List<Integer> labelsAt(MethodNode method, int instructionIndex) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            if (!(method.instructions.get(index) instanceof LabelNode)) continue;
            int next = nextExecutableIndex(method, index + 1);
            if (next == instructionIndex) result.add(index);
        }
        return result;
    }

    private static int rangeBoundaryInsertionIndex(MethodNode method, int instructionIndex) {
        List<Integer> labels = labelsAt(method, instructionIndex);
        return labels.isEmpty() ? instructionIndex : labels.getFirst();
    }

    private static boolean sameThrowBlock(MethodNode method, ThrowBlock first, ThrowBlock second) {
        List<AbstractInsnNode> firstInstructions = executableInstructions(method,
                first.startIndex(), first.endIndex());
        List<AbstractInsnNode> secondInstructions = executableInstructions(method,
                second.startIndex(), second.endIndex());
        if (firstInstructions.size() != secondInstructions.size()) return false;
        for (int index = 0; index < firstInstructions.size(); index++) {
            AbstractInsnNode left = firstInstructions.get(index);
            AbstractInsnNode right = secondInstructions.get(index);
            if (left.getType() != right.getType() || left.getOpcode() != right.getOpcode()) return false;
            if (left instanceof VarInsnNode leftVar && right instanceof VarInsnNode rightVar
                    && leftVar.var != rightVar.var) return false;
            if (left instanceof TypeInsnNode leftType && right instanceof TypeInsnNode rightType
                    && !leftType.desc.equals(rightType.desc)) return false;
            if (left instanceof FieldInsnNode leftField && right instanceof FieldInsnNode rightField
                    && (!leftField.owner.equals(rightField.owner)
                    || !leftField.name.equals(rightField.name)
                    || !leftField.desc.equals(rightField.desc))) return false;
            if (left instanceof MethodInsnNode leftInvoke && right instanceof MethodInsnNode rightInvoke
                    && (!leftInvoke.owner.equals(rightInvoke.owner)
                    || !leftInvoke.name.equals(rightInvoke.name)
                    || !leftInvoke.desc.equals(rightInvoke.desc)
                    || leftInvoke.itf != rightInvoke.itf)) return false;
            if (left instanceof LdcInsnNode leftLdc && right instanceof LdcInsnNode rightLdc
                    && !java.util.Objects.equals(leftLdc.cst, rightLdc.cst)) return false;
            if (left instanceof IntInsnNode leftInt && right instanceof IntInsnNode rightInt
                    && leftInt.operand != rightInt.operand) return false;
        }
        return true;
    }

    private static List<AbstractInsnNode> executableInstructions(MethodNode method, int start, int end) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (int index = start; index <= end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() != AbstractInsnNode.LABEL
                    && instruction.getType() != AbstractInsnNode.FRAME
                    && instruction.getType() != AbstractInsnNode.LINE)
                result.add(instruction);
        }
        return result;
    }

    private record ThrowBlock(LabelNode label, JumpInsnNode branch, int startIndex, int endIndex) {}

    private static boolean isConditional(int opcode) {
        return opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE
                || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL;
    }

    private static int invertConditional(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ -> Opcodes.IFNE;
            case Opcodes.IFNE -> Opcodes.IFEQ;
            case Opcodes.IFLT -> Opcodes.IFGE;
            case Opcodes.IFGE -> Opcodes.IFLT;
            case Opcodes.IFGT -> Opcodes.IFLE;
            case Opcodes.IFLE -> Opcodes.IFGT;
            case Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE;
            case Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ;
            case Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE;
            case Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT;
            case Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE;
            case Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT;
            case Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE;
            case Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ;
            case Opcodes.IFNULL -> Opcodes.IFNONNULL;
            case Opcodes.IFNONNULL -> Opcodes.IFNULL;
            default -> throw new IllegalArgumentException("Not a conditional opcode: " + opcode);
        };
    }

    private static TryCatchBlockNode containingCatchAll(MethodNode method, int sourceIndex, int targetIndex) {
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(range.start);
            int end = method.instructions.indexOf(range.end);
            if (range.type == null && start <= sourceIndex && sourceIndex < end
                    && !(start <= targetIndex && targetIndex < end)) return range;
        }
        return null;
    }

    private static int straightLineThrowEnd(MethodNode method, int start) {
        int throwIndex = -1;
        for (int index = start + 1; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode) break;
            if (instruction.getType() == AbstractInsnNode.FRAME
                    || instruction.getType() == AbstractInsnNode.LINE) continue;
            if (instruction instanceof JumpInsnNode
                    || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode) return -1;
            if (instruction.getOpcode() == Opcodes.ATHROW) {
                throwIndex = index;
                break;
            }
            int opcode = instruction.getOpcode();
            if (instruction instanceof VarInsnNode
                    || instruction instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)
                    || instruction instanceof TypeInsnNode
                    || instruction instanceof MethodInsnNode
                    || isConstantInstruction(instruction)
                    || opcode == Opcodes.DUP || opcode == Opcodes.POP || opcode == Opcodes.SWAP
                    || opcode == Opcodes.CHECKCAST) continue;
            return -1;
        }
        if (throwIndex < 0) return -1;
        for (int index = throwIndex + 1; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() == AbstractInsnNode.FRAME
                    || instruction.getType() == AbstractInsnNode.LINE) continue;
            if (instruction instanceof LabelNode) break;
            return -1;
        }
        return throwIndex;
    }

    private static int labelIncomingCount(MethodNode method, LabelNode target) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.label == target) count++;
            if (instruction instanceof TableSwitchInsnNode table
                    && (table.dflt == target || table.labels.contains(target))) count++;
            if (instruction instanceof LookupSwitchInsnNode lookup
                    && (lookup.dflt == target || lookup.labels.contains(target))) count++;
        }
        return count;
    }

    private static boolean allIncomingThrowBranchesAreProtected(MethodNode method,
                                                                  LabelNode target,
                                                                  int targetIndex) {
        boolean found = false;
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (!(instruction instanceof JumpInsnNode jump) || jump.label != target) continue;
            found = true;
            if (!isConditional(jump.getOpcode()) || containingCatchAll(method, index, targetIndex) == null)
                return false;
        }
        return found;
    }

    private static int cleanupTailEnd(MethodNode method, int start) {
        boolean sawTerminal = false;
        for (int index = start; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() == AbstractInsnNode.LABEL
                    || instruction.getType() == AbstractInsnNode.FRAME
                    || instruction.getType() == AbstractInsnNode.LINE) continue;
            if (instruction instanceof JumpInsnNode jump) {
                if (jump.getOpcode() != Opcodes.IFNULL && jump.getOpcode() != Opcodes.IFNONNULL)
                    return -1;
                continue;
            }
            if (isTerminal(instruction)) {
                if (instruction.getOpcode() != Opcodes.RETURN || sawTerminal) return -1;
                sawTerminal = true;
                return index;
            }
        }
        return -1;
    }

    private static boolean isLocalCleanupTail(MethodNode method, int start, int end) {
        int removeCount = 0;
        Set<Integer> keyLocals = new HashSet<>();
        for (int index = start; index <= end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() == AbstractInsnNode.LABEL
                    || instruction.getType() == AbstractInsnNode.FRAME
                    || instruction.getType() == AbstractInsnNode.LINE
                    || instruction.getOpcode() == Opcodes.POP
                    || instruction instanceof VarInsnNode
                    || instruction instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)
                    || isConstantInstruction(instruction)) continue;
            if (instruction instanceof JumpInsnNode jump) {
                if (jump.getOpcode() != Opcodes.IFNULL && jump.getOpcode() != Opcodes.IFNONNULL)
                    return false;
                continue;
            }
            if (instruction instanceof MethodInsnNode invoke
                    && (invoke.getOpcode() == Opcodes.INVOKEVIRTUAL
                    || invoke.getOpcode() == Opcodes.INVOKEINTERFACE)
                    && "remove".equals(invoke.name)
                    && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(invoke.desc)) {
                int argument = previousExecutableIndex(method, index - 1);
                if (argument < start || !(method.instructions.get(argument) instanceof VarInsnNode load)
                        || load.getOpcode() != Opcodes.ALOAD) return false;
                keyLocals.add(load.var);
                removeCount++;
                continue;
            }
            if (instruction.getOpcode() == Opcodes.RETURN) continue;
            return false;
        }
        return removeCount >= 2 && keyLocals.size() == 1;
    }

    private static int tailIncomingCount(MethodNode method, LabelNode target) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.label == target) count++;
            if (instruction instanceof TableSwitchInsnNode table
                    && (table.dflt == target || table.labels.contains(target))) count++;
            if (instruction instanceof LookupSwitchInsnNode lookup
                    && (lookup.dflt == target || lookup.labels.contains(target))) count++;
        }
        int targetIndex = method.instructions.indexOf(target);
        int previous = previousExecutableIndex(method, targetIndex - 1);
        if (previous >= 0) {
            AbstractInsnNode predecessor = method.instructions.get(previous);
            // A conditional handler/cleanup path may reach the shared suffix
            // by ordinary fall-through rather than a GOTO.  Count that edge
            // so the normal-path duplication proof recognizes both forms.
            if (!isTerminal(predecessor)
                    && !(predecessor instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO)
                    && !(predecessor instanceof TableSwitchInsnNode)
                    && !(predecessor instanceof LookupSwitchInsnNode)) count++;
        }
        return count;
    }

    private static boolean onlyLocalLoadsBetween(MethodNode method, int start, int end) {
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() == AbstractInsnNode.LINE
                    || instruction.getType() == AbstractInsnNode.FRAME) continue;
            if (instruction instanceof LabelNode label
                    && isUnreferencedFallthroughLabel(method, label, index)) continue;
            if (!(instruction instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ALOAD)
                return false;
        }
        return true;
    }

    /**
     * Tightens catch-all ranges for constructor-backed resources to the first
     * instruction after successful construction.  DEX lowering commonly
     * starts every nested resource range at the outer block, even though the
     * JVM try-with-resources shape starts the inner range after its resource
     * constructor.  The adjustment is deliberately limited to ranges whose
     * handler contains a matching close receiver and constructor, so an outer
     * envelope or an unrelated catch-all is never shortened speculatively.
     *
     * <p>This is a layout-only transformation.  It does not move bytecode or
     * alter handler order; callers must run the normal shape/verifier checks
     * after applying it.</p>
     */
    static int normalizeResourceRangeStarts(MethodNode method) {
        int changed = 0;
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            if (range.type != null) continue;
            int start = method.instructions.indexOf(range.start);
            int end = method.instructions.indexOf(range.end);
            int handler = method.instructions.indexOf(range.handler);
            if (start < 0 || end <= start || handler < 0) continue;

            ResourceClose close = firstHandlerClose(method, handler);
            if (close == null) continue;
            for (int index = start; index < end; index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                if (!(instruction instanceof MethodInsnNode invoke)
                        || invoke.getOpcode() != Opcodes.INVOKESPECIAL
                        || !"<init>".equals(invoke.name)
                        || !close.owner().equals(invoke.owner)
                        || !hasResourceConstructorShape(method, index, end, close.local())) continue;

                LabelNode boundary = new LabelNode();
                method.instructions.insert(instruction, boundary);
                range.start = boundary;
                changed++;
                break;
            }
        }
        return changed;
    }

    /**
     * Reconstructs the canonical JVM resource-acquisition expression for a
     * constructor whose close handler proves that it is a resource local.
     * The local-first emitter normally produces {@code NEW; ASTORE; acquire;
     * ALOAD; <init>}.  Within a straight-line acquisition slice this can be
     * represented as {@code NEW; DUP; acquire; <init>; ASTORE}, which is the
     * form javac emits for try-with-resources and which decompilers recognize
     * without inventing an acquisition temporary.
     */
    static int normalizeResourceConstructors(MethodNode method) {
        Set<ResourceClose> resources = resourceClosePairs(method);
        if (resources.isEmpty()) return 0;
        int changed = 0;
        for (int index = 0; index + 1 < method.instructions.size(); index++) {
            AbstractInsnNode allocationNode = method.instructions.get(index);
            if (!(allocationNode instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW) continue;
            int storeIndex = nextExecutableIndex(method, index + 1);
            if (storeIndex < 0 || !(method.instructions.get(storeIndex) instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ASTORE
                    || resources.stream().noneMatch(resource -> resource.owner().equals(allocation.desc)
                    && resource.local() == store.var)) continue;

            MethodInsnNode constructor = null;
            for (int cursor = storeIndex + 1; cursor < Math.min(method.instructions.size(), storeIndex + 32); cursor++) {
                AbstractInsnNode candidate = method.instructions.get(cursor);
                if (candidate.getType() == AbstractInsnNode.LABEL) {
                    if (isRangeBoundary(method, (LabelNode) candidate)) break;
                    continue;
                }
                if (candidate.getType() == AbstractInsnNode.FRAME
                        || candidate instanceof JumpInsnNode) break;
                if (candidate instanceof MethodInsnNode invoke
                        && invoke.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(invoke.name)
                        && allocation.desc.equals(invoke.owner)) {
                    constructor = invoke;
                    break;
                }
            }
            if (constructor == null) continue;
            int constructorIndex = method.instructions.indexOf(constructor);
            int argumentLoadIndex = previousExecutableIndex(method, constructorIndex - 1);
            int receiverLoadIndex = previousExecutableIndex(method, argumentLoadIndex - 1);
            if (argumentLoadIndex < 0 || receiverLoadIndex < 0
                    || !(method.instructions.get(receiverLoadIndex) instanceof VarInsnNode receiver)
                    || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != store.var) continue;

            // The object under construction must not be observed between its
            // early store and the constructor receiver load.
            if (hasLocalReadBetween(method, storeIndex + 1, receiverLoadIndex, store.var)) continue;
            AbstractInsnNode argumentLoadNode = method.instructions.get(argumentLoadIndex);
            if (!(argumentLoadNode instanceof VarInsnNode argument)
                    || argument.getOpcode() != Opcodes.ALOAD) continue;
            int argumentStoreIndex = previousExecutableIndex(method, receiverLoadIndex - 1);
            if (argumentStoreIndex < 0 || !(method.instructions.get(argumentStoreIndex) instanceof VarInsnNode argumentStore)
                    || argumentStore.getOpcode() != Opcodes.ASTORE || argumentStore.var != argument.var) continue;
            // The acquisition argument is a short-lived producer local.  Its
            // relevant proof is whether it is read before its first later
            // assignment, not whether a detached cleanup route can be reached
            // by the conservative normal-flow walk.  The latter rejects the
            // canonical javac shape when DEX-split handler glue is laid out
            // linearly after the acquisition.
            if (hasReadBeforeStore(method, argumentLoadIndex + 1, argument.var)) continue;

            method.instructions.remove(argumentLoadNode);
            method.instructions.remove(argumentStore);
            method.instructions.remove(store);
            method.instructions.remove(receiver);
            method.instructions.insert(allocationNode, new InsnNode(Opcodes.DUP));
            method.instructions.insert(constructor, new VarInsnNode(Opcodes.ASTORE, store.var));
            changed++;
            index = method.instructions.indexOf(constructor);
        }
        return changed;
    }

    private static Set<ResourceClose> resourceClosePairs(MethodNode method) {
        Set<ResourceClose> result = new HashSet<>();
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            if (range.type != null) continue;
            int handler = method.instructions.indexOf(range.handler);
            if (handler < 0) continue;
            ResourceClose close = firstHandlerClose(method, handler);
            if (close != null) result.add(close);
        }
        return result;
    }

    private static boolean hasResourceConstructorShape(MethodNode method, int constructorIndex, int end, int local) {
        if (hasReceiverLoadBefore(method, constructorIndex, local)) return true;
        int next = nextExecutableIndex(method, constructorIndex + 1);
        return next >= 0 && next < end && method.instructions.get(next) instanceof VarInsnNode store
                && store.getOpcode() == Opcodes.ASTORE && store.var == local;
    }

    private static boolean hasLocalReadBetween(MethodNode method, int start, int end, int local) {
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof VarInsnNode variable && variable.var == local && isLoad(variable.getOpcode()))
                return true;
        }
        return false;
    }

    private static boolean hasReadBeforeStore(MethodNode method, int start, int local) {
        for (int index = start; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (!(instruction instanceof VarInsnNode variable) || variable.var != local) continue;
            int opcode = variable.getOpcode();
            if (isStore(opcode)) return false;
            if (isLoad(opcode) || opcode == Opcodes.IINC) return true;
        }
        return false;
    }

    private static ResourceClose firstHandlerClose(MethodNode method, int handler) {
        for (int index = handler; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (!(instruction instanceof MethodInsnNode invoke) || !"close".equals(invoke.name)) continue;
            int local = -1;
            for (int prior = index - 1; prior >= handler; prior--) {
                AbstractInsnNode previous = method.instructions.get(prior);
                if (previous instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD) {
                    local = variable.var;
                    break;
                }
            }
            if (local >= 0) return new ResourceClose(invoke.owner, local);
        }
        return null;
    }

    private static boolean hasReceiverLoadBefore(MethodNode method, int invokeIndex, int local) {
        for (int index = invokeIndex - 1; index >= Math.max(0, invokeIndex - 12); index--) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof VarInsnNode variable
                    && variable.getOpcode() == Opcodes.ALOAD && variable.var == local) return true;
        }
        return false;
    }

    private record ResourceClose(String owner, int local) {}

    private static boolean stackReceiverIntervening(MethodNode method, int start, int end, int consumerIndex) {
        AbstractInsnNode receiverLoad = method.instructions.get(consumerIndex);
        if (!(receiverLoad instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return false;

        // The producer must be immediately below the receiver load.  If
        // arguments were pushed before the load, removing the local would
        // change JVM evaluation order and stack order.
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() == AbstractInsnNode.LINE
                    || instruction.getType() == AbstractInsnNode.FRAME) continue;
            if (instruction.getType() == AbstractInsnNode.LABEL
                    && isUnreferencedFallthroughLabel(method, (LabelNode) instruction, index)) continue;
            return false;
        }

        int aboveReceiver = 0;
        for (int index = consumerIndex + 1; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() == AbstractInsnNode.LINE
                    || instruction.getType() == AbstractInsnNode.FRAME) continue;
            if (instruction.getType() == AbstractInsnNode.LABEL
                    || instruction instanceof JumpInsnNode
                    || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode
                    || isTerminal(instruction)) return false;

            if (instruction instanceof VarInsnNode variable && isLoad(variable.getOpcode())) {
                aboveReceiver += localCategory(variable.getOpcode());
                continue;
            }
            if (isConstantInstruction(instruction)) {
                aboveReceiver += constantCategory(instruction);
                continue;
            }
            if (instruction instanceof FieldInsnNode field) {
                int fieldCategory = typeCategory(Type.getType(field.desc));
                if (field.getOpcode() == Opcodes.GETSTATIC) {
                    aboveReceiver += fieldCategory;
                    continue;
                }
                if (field.getOpcode() == Opcodes.GETFIELD) {
                    if (aboveReceiver < 1) return false;
                    aboveReceiver--;
                    aboveReceiver += fieldCategory;
                    continue;
                }
                return false;
            }
            if (instruction instanceof MethodInsnNode invoke) {
                Type methodType = Type.getMethodType(invoke.desc);
                int arguments = argumentCategory(methodType);
                int consumed = arguments + (invoke.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
                if (aboveReceiver >= consumed) {
                    aboveReceiver -= consumed;
                    aboveReceiver += typeCategory(methodType.getReturnType());
                    continue;
                }
                // This is the one permitted operation that consumes the
                // retained producer as its receiver.  All arguments must
                // already be above it, and no later operation is allowed to
                // observe the old local.
                return invoke.getOpcode() != Opcodes.INVOKESTATIC
                        && invoke.getOpcode() != Opcodes.INVOKESPECIAL
                        && aboveReceiver == arguments;
            }
            if (instruction instanceof InvokeDynamicInsnNode invokeDynamic) {
                Type methodType = Type.getMethodType(invokeDynamic.desc);
                int arguments = argumentCategory(methodType);
                if (aboveReceiver < arguments) return false;
                aboveReceiver -= arguments;
                aboveReceiver += typeCategory(methodType.getReturnType());
                continue;
            }
            if (instruction instanceof TypeInsnNode typeInstruction) {
                if (typeInstruction.getOpcode() == Opcodes.CHECKCAST) continue;
                if (typeInstruction.getOpcode() == Opcodes.NEW) {
                    aboveReceiver++;
                    continue;
                }
                return false;
            }
            return false;
        }
        return false;
    }

    private static int argumentCategory(Type methodType) {
        int category = 0;
        for (Type argument : methodType.getArgumentTypes()) category += typeCategory(argument);
        return category;
    }

    private static int typeCategory(Type type) {
        if (type == Type.VOID_TYPE) return 0;
        return type == Type.LONG_TYPE || type == Type.DOUBLE_TYPE ? 2 : 1;
    }

    private static int localCategory(int opcode) {
        return opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD ? 2 : 1;
    }

    private static int constantCategory(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode ldc
                && (ldc.cst instanceof Long || ldc.cst instanceof Double)) return 2;
        int opcode = instruction.getOpcode();
        return opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1
                || opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1 ? 2 : 1;
    }

    private static List<AbstractInsnNode> invocationProducerSlice(MethodNode method, int producerIndex,
                                                                   MethodInsnNode producer) {
        List<AbstractInsnNode> slice = new ArrayList<>();
        Type methodType = Type.getMethodType(producer.desc);
        Type[] arguments = methodType.getArgumentTypes();
        if (producer.getOpcode() == Opcodes.INVOKESTATIC && arguments.length == 0) {
            slice.add(producer);
            return slice;
        }
        if (producer.getOpcode() == Opcodes.INVOKESTATIC && arguments.length == 1) {
            int argumentIndex = previousExecutableIndex(method, producerIndex - 1);
            if (argumentIndex >= 0 && isConstantInstruction(method.instructions.get(argumentIndex))) {
                slice.add(method.instructions.get(argumentIndex));
                slice.add(producer);
                return slice;
            }
            return List.of();
        }
        if (arguments.length != 0 || producer.getOpcode() == Opcodes.INVOKESTATIC) return List.of();
        int receiverIndex = previousExecutableIndex(method, producerIndex - 1);
        if (receiverIndex < 0 || !(method.instructions.get(receiverIndex) instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETFIELD || !"L".equals(field.desc.substring(0, 1))) return List.of();
        int ownerIndex = previousExecutableIndex(method, receiverIndex - 1);
        if (ownerIndex < 0 || !(method.instructions.get(ownerIndex) instanceof VarInsnNode receiver)
                || receiver.getOpcode() != Opcodes.ALOAD) return List.of();
        slice.add(receiver);
        slice.add(field);
        slice.add(producer);
        return slice;
    }

    /**
     * Removes a proven straight-line local copy.  The source value is loaded
     * again at its only consumer, so this never carries a JVM stack value over
     * an intervening instruction or control-flow boundary.
     */
    static int removeRedundantLocalCopies(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int storeIndex = 0; storeIndex < method.instructions.size(); storeIndex++) {
                AbstractInsnNode storeNode = method.instructions.get(storeIndex);
                if (!(storeNode instanceof VarInsnNode store) || !isStore(store.getOpcode())) continue;
                int sourceIndex = previousMetadataOnlyInstruction(method, storeIndex - 1);
                if (sourceIndex < 0 || !(method.instructions.get(sourceIndex) instanceof VarInsnNode source)) continue;
                if (!isLoad(source.getOpcode()) || source.var == store.var
                        || loadOpcode(store.getOpcode()) != source.getOpcode()) continue;

                int consumerIndex = firstCopyUse(method, storeIndex + 1, store.var);
                if (consumerIndex < 0) continue;
                AbstractInsnNode consumerNode = method.instructions.get(consumerIndex);
                if (!(consumerNode instanceof VarInsnNode consumer)
                        || consumer.getOpcode() != loadOpcode(store.getOpcode())) continue;
                if (!sameProtectedProfile(method, source, storeNode)
                        || !sameProtectedProfile(method, storeNode, consumerNode)) continue;
                if (isProtected(method, storeNode) && handlerUsesLocal(method, storeNode, store.var)) continue;
                if (sourceLocalMutated(method, sourceIndex + 1, consumerIndex, source.var)) continue;

                method.instructions.set(consumerNode, new VarInsnNode(source.getOpcode(), source.var));
                method.instructions.remove(storeNode);
                method.instructions.remove(source);
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Removes a transient store/reload used only to feed a second local. The
     * producer remains on the stack while the destination store is performed:
     * {@code producer; ASTORE source; ALOAD source; ASTORE destination} becomes
     * {@code producer; ASTORE destination}. This is useful for SSA edge copies
     * where the source slot is immediately overwritten before any read. The
     * proof stays straight-line and frame/profile aware; handlers and a read
     * of the source before its next store keep the copy.
     */
    static int removeRedundantStoreReloadStores(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int storeIndex = 0; storeIndex < method.instructions.size(); storeIndex++) {
                AbstractInsnNode sourceNode = method.instructions.get(storeIndex);
                if (!(sourceNode instanceof VarInsnNode source) || !isStore(source.getOpcode())) continue;
                int loadIndex = nextMaterializationInstruction(method, storeIndex + 1);
                if (loadIndex < 0 || !(method.instructions.get(loadIndex) instanceof VarInsnNode reload)
                        || !isLoad(reload.getOpcode()) || reload.var != source.var
                        || reload.getOpcode() != loadOpcode(source.getOpcode())) continue;
                int destinationIndex = nextMaterializationInstruction(method, loadIndex + 1);
                if (destinationIndex < 0 || !(method.instructions.get(destinationIndex) instanceof VarInsnNode destination)
                        || !isStore(destination.getOpcode()) || destination.var == source.var
                        || loadOpcode(destination.getOpcode()) != reload.getOpcode()) continue;
                if (!sameProtectedProfile(method, sourceNode, destination)
                        || !sameProtectedProfile(method, reload, destination)) continue;
                if (isProtected(method, sourceNode)
                        && (handlerUsesLocal(method, sourceNode, source.var)
                        || handlerUsesLocal(method, sourceNode, destination.var))) continue;
                // The normal-flow walk is intentionally conservative around
                // loops.  A relay at a block entry can still be eliminated
                // when the source slot is provably dead for the remainder of
                // the method: there is no later read, write, or increment of
                // that slot.  This preserves the frame/category proof while
                // allowing request-id style SSA copies to collapse without
                // inventing a decompiler-visible alias.
                if (hasReadBeforeReassignment(method, destinationIndex + 1, source.var)
                        && hasAnyLocalAccess(method, destinationIndex + 1, source.var)) continue;

                method.instructions.remove(reload);
                method.instructions.remove(sourceNode);
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Removes the raw-reference slot around a one-use cast:
     * {@code producer; ASTORE raw; ALOAD raw; CHECKCAST T; ASTORE typed}.
     * The cast remains at the consumer, preserving its exception behavior and
     * verifier category while avoiding a decompiler-visible Object alias.
     */
    static int removeRedundantCastStores(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int storeIndex = 0; storeIndex < method.instructions.size(); storeIndex++) {
                AbstractInsnNode sourceNode = method.instructions.get(storeIndex);
                if (!(sourceNode instanceof VarInsnNode source) || source.getOpcode() != Opcodes.ASTORE) continue;
                int loadIndex = nextMaterializationInstruction(method, storeIndex + 1);
                if (loadIndex < 0 || !(method.instructions.get(loadIndex) instanceof VarInsnNode reload)
                        || reload.getOpcode() != Opcodes.ALOAD || reload.var != source.var) continue;
                int castIndex = nextMaterializationInstruction(method, loadIndex + 1);
                if (castIndex < 0 || !(method.instructions.get(castIndex) instanceof TypeInsnNode cast)
                        || cast.getOpcode() != Opcodes.CHECKCAST) continue;
                int destinationIndex = nextMaterializationInstruction(method, castIndex + 1);
                if (destinationIndex < 0 || !(method.instructions.get(destinationIndex) instanceof VarInsnNode destination)
                        || destination.getOpcode() != Opcodes.ASTORE || destination.var == source.var) continue;
                if (!sameProtectedProfile(method, sourceNode, cast)
                        || !sameProtectedProfile(method, cast, destination)) continue;
                // A handler can be laid out before or after the consumer, so
                // the normal-flow reassignment proof alone is insufficient.
                // If the reload is the only read of the raw slot anywhere in
                // the staged method, removing its store cannot change a frame
                // or handler observation of that slot.
                if (hasOtherLocalRead(method, source.var, reload)) continue;

                method.instructions.remove(reload);
                method.instructions.remove(sourceNode);
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Folds a non-handler bridge of the form {@code ALOAD s; ASTORE d; GOTO
     * target} into its sole unconditional incoming edge.  The destination
     * local is deliberately retained, so this changes only label topology:
     * the value is materialized at the edge and the bridge label disappears.
     * Protected-range boundaries, handler entries, fall-through edges, and
     * multi-predecessor bridges are rejected.
     */
    static int removeUnconditionalCopyBridges(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int bridgeIndex = 0; bridgeIndex < method.instructions.size(); bridgeIndex++) {
                AbstractInsnNode bridgeNode = method.instructions.get(bridgeIndex);
                if (!(bridgeNode instanceof LabelNode bridge) || isRangeBoundary(method, bridge)) continue;
                int loadIndex = nextExecutableIndex(method, bridgeIndex + 1);
                int storeIndex = nextExecutableIndex(method, loadIndex + 1);
                int jumpIndex = nextExecutableIndex(method, storeIndex + 1);
                if (loadIndex < 0 || storeIndex < 0 || jumpIndex < 0
                        || !(method.instructions.get(loadIndex) instanceof VarInsnNode load)
                        || !(method.instructions.get(storeIndex) instanceof VarInsnNode store)
                        || !(method.instructions.get(jumpIndex) instanceof JumpInsnNode jump)
                        || jump.getOpcode() != Opcodes.GOTO || !isLoad(load.getOpcode())
                        || !isStore(store.getOpcode()) || loadOpcode(store.getOpcode()) != load.getOpcode()) continue;
                if (isRangeBoundary(method, jump.label)) continue;
                int previousIndex = previousExecutableIndex(method, bridgeIndex - 1);
                if (previousIndex < 0 || !(method.instructions.get(previousIndex) instanceof JumpInsnNode incoming)
                        || incoming.getOpcode() != Opcodes.GOTO || incoming.label != bridge) continue;
                int incomingCount = 0;
                boolean switchIncoming = false;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof JumpInsnNode candidate && candidate.label == bridge) incomingCount++;
                    if (instruction instanceof TableSwitchInsnNode table
                            && (table.dflt == bridge || table.labels.contains(bridge))) switchIncoming = true;
                    if (instruction instanceof LookupSwitchInsnNode lookup
                            && (lookup.dflt == bridge || lookup.labels.contains(bridge))) switchIncoming = true;
                }
                if (incomingCount != 1 || switchIncoming) continue;
                if (!sameProtectedProfile(method, load, incoming)) continue;

                method.instructions.insertBefore(incoming, new VarInsnNode(load.getOpcode(), load.var));
                method.instructions.insertBefore(incoming, new VarInsnNode(store.getOpcode(), store.var));
                incoming.label = jump.label;
                for (int index = jumpIndex; index >= bridgeIndex; index--)
                    method.instructions.remove(method.instructions.get(index));
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Propagates a copy bridge entered by one conditional edge into its sole
     * target.  Unlike the unconditional form above, the incoming branch must
     * retain its edge semantics; only the target's loads are rewritten to the
     * source local before the bridge is removed.  This is the safe shape for
     * cleanup joins where a conditional edge would otherwise decompile as a
     * one-iteration {@code while (true)} block.
     */
    static int removeSingleIncomingCopyBridges(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int bridgeIndex = 0; bridgeIndex < method.instructions.size(); bridgeIndex++) {
                if (!(method.instructions.get(bridgeIndex) instanceof LabelNode bridge)
                        || isRangeBoundary(method, bridge)) continue;
                int loadIndex = nextExecutableIndex(method, bridgeIndex + 1);
                int storeIndex = nextExecutableIndex(method, loadIndex + 1);
                int jumpIndex = nextExecutableIndex(method, storeIndex + 1);
                if (loadIndex < 0 || storeIndex < 0 || jumpIndex < 0
                        || !(method.instructions.get(loadIndex) instanceof VarInsnNode load)
                        || !(method.instructions.get(storeIndex) instanceof VarInsnNode store)
                        || !(method.instructions.get(jumpIndex) instanceof JumpInsnNode jump)
                        || jump.getOpcode() != Opcodes.GOTO || !isLoad(load.getOpcode())
                        || !isStore(store.getOpcode()) || loadOpcode(store.getOpcode()) != load.getOpcode()
                        || isRangeBoundary(method, jump.label) || isHandlerLabel(method, jump.label)) continue;

                JumpInsnNode incoming = null;
                int incomingCount = 0;
                boolean switchIncoming = false;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof JumpInsnNode candidate && candidate.label == bridge) {
                        incoming = candidate;
                        incomingCount++;
                    }
                    if (instruction instanceof TableSwitchInsnNode table
                            && (table.dflt == bridge || table.labels.contains(bridge))) switchIncoming = true;
                    if (instruction instanceof LookupSwitchInsnNode lookup
                            && (lookup.dflt == bridge || lookup.labels.contains(bridge))) switchIncoming = true;
                }
                if (incomingCount != 1 || incoming == null || switchIncoming
                        || incoming.getOpcode() == Opcodes.GOTO) continue;

                int targetIncoming = 0;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof JumpInsnNode candidate && candidate.label == jump.label) targetIncoming++;
                    if (instruction instanceof TableSwitchInsnNode table
                            && (table.dflt == jump.label || table.labels.contains(jump.label))) targetIncoming++;
                    if (instruction instanceof LookupSwitchInsnNode lookup
                            && (lookup.dflt == jump.label || lookup.labels.contains(jump.label))) targetIncoming++;
                }
                if (targetIncoming != 1 || !sameProtectedProfile(method, load, incoming)) continue;
                Set<Integer> reachable = normalReachableFromLabel(method, jump.label,
                        instructionIndices(method));
                if (reachable.isEmpty()) continue;

                List<VarInsnNode> targetLoads = new ArrayList<>();
                boolean invalid = false;
                for (int index : reachable) {
                    AbstractInsnNode instruction = method.instructions.get(index);
                    if (!(instruction instanceof VarInsnNode variable)) continue;
                    if (variable.var == load.var && isStore(variable.getOpcode())) {
                        invalid = true;
                        break;
                    }
                    if (variable.var == store.var) {
                        if (isStore(variable.getOpcode())) {
                            invalid = true;
                            break;
                        }
                        if (isLoad(variable.getOpcode())) targetLoads.add(variable);
                    }
                }
                if (invalid || targetLoads.isEmpty()) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof VarInsnNode variable && variable.var == store.var
                            && isLoad(variable.getOpcode()) && !targetLoads.contains(variable)) {
                        invalid = true;
                        break;
                    }
                }
                if (invalid || isProtected(method, load) && handlerUsesLocal(method, load, store.var)) continue;

                for (VarInsnNode targetLoad : targetLoads) {
                    if (targetLoad.getOpcode() != load.getOpcode()) {
                        invalid = true;
                        break;
                    }
                }
                if (invalid) continue;
                for (VarInsnNode targetLoad : targetLoads)
                    method.instructions.set(targetLoad, new VarInsnNode(load.getOpcode(), load.var));
                method.instructions.remove(jump);
                method.instructions.remove(store);
                method.instructions.remove(load);
                method.instructions.remove(bridge);
                incoming.label = jump.label;
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    private static Map<AbstractInsnNode, Integer> instructionIndices(MethodNode method) {
        Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
        for (int index = 0; index < method.instructions.size(); index++)
            indices.put(method.instructions.get(index), index);
        return indices;
    }

    /**
     * Flattens a multi-source copy join.  Each incoming edge must copy a
     * source slot into the same intermediate slot, while the join copies that
     * intermediate into its final slot.  When the intermediate has no other
     * reads, each edge can write the final slot directly and jump to the join's
     * target.  This is the bytecode equivalent of composing two SSA phi moves;
     * all source branches remain distinct and no stack value crosses a label.
     */
    static int removeMultiSourceCopyBridges(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int joinIndex = 0; joinIndex < method.instructions.size(); joinIndex++) {
                AbstractInsnNode joinNode = method.instructions.get(joinIndex);
                if (!(joinNode instanceof LabelNode join) || isRangeBoundary(method, join)) continue;
                int joinLoadIndex = nextExecutableIndex(method, joinIndex + 1);
                int joinStoreIndex = nextExecutableIndex(method, joinLoadIndex + 1);
                int joinJumpIndex = nextExecutableIndex(method, joinStoreIndex + 1);
                if (joinLoadIndex < 0 || joinStoreIndex < 0 || joinJumpIndex < 0
                        || !(method.instructions.get(joinLoadIndex) instanceof VarInsnNode joinLoad)
                        || !(method.instructions.get(joinStoreIndex) instanceof VarInsnNode joinStore)
                        || !(method.instructions.get(joinJumpIndex) instanceof JumpInsnNode joinJump)
                        || joinJump.getOpcode() != Opcodes.GOTO || !isLoad(joinLoad.getOpcode())
                        || !isStore(joinStore.getOpcode()) || loadOpcode(joinStore.getOpcode()) != joinLoad.getOpcode()
                        || joinLoad.var == joinStore.var || isRangeBoundary(method, joinJump.label)) continue;
                if (hasOtherLocalRead(method, joinLoad.var, joinLoad)) continue;

                List<BridgeCopy> incoming = new ArrayList<>();
                boolean switchIncoming = false;
                for (int index = 0; index < method.instructions.size(); index++) {
                    AbstractInsnNode instruction = method.instructions.get(index);
                    if (instruction instanceof JumpInsnNode jump && jump.label == join) {
                        if (jump.getOpcode() != Opcodes.GOTO) {
                            incoming.clear();
                            switchIncoming = true;
                            break;
                        }
                        int storeIndex = previousExecutableIndex(method, index - 1);
                        int loadIndex = previousExecutableIndex(method, storeIndex - 1);
                        if (storeIndex < 0 || loadIndex < 0
                                || !(method.instructions.get(storeIndex) instanceof VarInsnNode store)
                                || !(method.instructions.get(loadIndex) instanceof VarInsnNode load)
                                || !isLoad(load.getOpcode()) || !isStore(store.getOpcode())
                                || store.var != joinLoad.var || loadOpcode(store.getOpcode()) != load.getOpcode()
                                || !sameProtectedProfile(method, load, joinLoad)
                                || !sameProtectedProfile(method, store, joinStore)) {
                            incoming.clear();
                            switchIncoming = true;
                            break;
                        }
                        incoming.add(new BridgeCopy(load, store, jump));
                    }
                    if (instruction instanceof TableSwitchInsnNode table
                            && (table.dflt == join || table.labels.contains(join))) switchIncoming = true;
                    if (instruction instanceof LookupSwitchInsnNode lookup
                            && (lookup.dflt == join || lookup.labels.contains(join))) switchIncoming = true;
                }
                if (switchIncoming || incoming.size() < 2) continue;
                int previousIndex = previousExecutableIndex(method, joinIndex - 1);
                if (previousIndex < 0 || !(method.instructions.get(previousIndex) instanceof JumpInsnNode previous)
                        || previous.getOpcode() != Opcodes.GOTO || previous.label != join) continue;

                for (BridgeCopy copy : incoming) {
                    copy.store().var = joinStore.var;
                    copy.jump().label = joinJump.label;
                }
                for (int index = joinJumpIndex; index >= joinIndex; index--)
                    method.instructions.remove(method.instructions.get(index));
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    private record BridgeCopy(VarInsnNode load, VarInsnNode store, JumpInsnNode jump) {}

    /**
     * Performs the same phi-copy composition as {@link
     * #removeMultiSourceCopyBridges(MethodNode)} when one predecessor reaches
     * the join by fall-through.  DEX cleanup lowering commonly leaves one
     * copy block immediately before the join label while the other sources
     * use explicit gotos.  Treating that fall-through as a real edge removes
     * the small infinite-loop-shaped glue blocks without moving any
     * expression across a label or protected boundary.
     */
    static int removeFallthroughCopyJoins(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int joinIndex = 0; joinIndex < method.instructions.size(); joinIndex++) {
                if (!(method.instructions.get(joinIndex) instanceof LabelNode join)
                        // Keep handler-entry labels hard: their one-item JVM
                        // exception stack cannot be treated as an ordinary
                        // local-phi join.  Protected start/end labels are
                        // retained in place, so removing only the stack-empty
                        // copy instructions does not change range coverage.
                        || isHandlerLabel(method, join)) continue;
                int joinLoadIndex = nextExecutableIndex(method, joinIndex + 1);
                int joinStoreIndex = nextExecutableIndex(method, joinLoadIndex + 1);
                int joinJumpIndex = nextExecutableIndex(method, joinStoreIndex + 1);
                if (joinLoadIndex < 0 || joinStoreIndex < 0 || joinJumpIndex < 0
                        || !(method.instructions.get(joinLoadIndex) instanceof VarInsnNode joinLoad)
                        || !(method.instructions.get(joinStoreIndex) instanceof VarInsnNode joinStore)
                        || !(method.instructions.get(joinJumpIndex) instanceof JumpInsnNode joinJump)
                        || joinJump.getOpcode() != Opcodes.GOTO || !isLoad(joinLoad.getOpcode())
                        || !isStore(joinStore.getOpcode()) || joinLoad.var == joinStore.var
                        || loadOpcode(joinStore.getOpcode()) != joinLoad.getOpcode()
                        || isRangeBoundary(method, joinJump.label)
                        || hasOtherLocalRead(method, joinLoad.var, joinLoad)) continue;

                List<JoinCopy> incoming = new ArrayList<>();
                boolean invalid = false;
                for (int index = 0; index < method.instructions.size(); index++) {
                    AbstractInsnNode instruction = method.instructions.get(index);
                    if (instruction instanceof JumpInsnNode jump && jump.label == join) {
                        if (jump.getOpcode() != Opcodes.GOTO) {
                            invalid = true;
                            break;
                        }
                        int storeIndex = previousExecutableIndex(method, index - 1);
                        int loadIndex = previousExecutableIndex(method, storeIndex - 1);
                        if (storeIndex < 0 || loadIndex < 0
                                || !(method.instructions.get(storeIndex) instanceof VarInsnNode store)
                                || !(method.instructions.get(loadIndex) instanceof VarInsnNode load)
                                || !isCopyPair(load, store, joinLoad)
                                || !sameProtectedProfile(method, load, joinLoad)
                                || !sameProtectedProfile(method, store, joinStore)) {
                            invalid = true;
                            break;
                        }
                        incoming.add(new JoinCopy(load, store, jump, false));
                    }
                    if (instruction instanceof TableSwitchInsnNode table
                            && (table.dflt == join || table.labels.contains(join))) invalid = true;
                    if (instruction instanceof LookupSwitchInsnNode lookup
                            && (lookup.dflt == join || lookup.labels.contains(join))) invalid = true;
                }
                if (invalid) continue;

                int fallthroughStoreIndex = previousExecutableIndex(method, joinIndex - 1);
                int fallthroughLoadIndex = previousExecutableIndex(method, fallthroughStoreIndex - 1);
                if (fallthroughStoreIndex >= 0 && fallthroughLoadIndex >= 0
                        && method.instructions.get(fallthroughStoreIndex) instanceof VarInsnNode fallthroughStore
                        && method.instructions.get(fallthroughLoadIndex) instanceof VarInsnNode fallthroughLoad
                        && isCopyPair(fallthroughLoad, fallthroughStore, joinLoad)
                        && sameProtectedProfile(method, fallthroughLoad, joinLoad)
                        && sameProtectedProfile(method, fallthroughStore, joinStore)) {
                    incoming.add(new JoinCopy(fallthroughLoad, fallthroughStore, null, true));
                }
                if (incoming.size() < 2) continue;

                // A direct fall-through source must not itself be the target
                // of a branch.  Otherwise its copy may be executed by an
                // additional predecessor not represented in this proof.
                JoinCopy fallthrough = incoming.stream().filter(JoinCopy::fallthrough).findFirst().orElse(null);
                if (fallthrough != null) {
                    int sourceIndex = method.instructions.indexOf(fallthrough.load());
                    int sourceLabel = previousLabelIndex(method, sourceIndex);
                    if (sourceLabel >= 0 && (isRangeBoundary(method, (LabelNode) method.instructions.get(sourceLabel))
                            || hasNonGotoIncomingEdge(method, (LabelNode) method.instructions.get(sourceLabel))))
                        continue;
                }

                for (JoinCopy copy : incoming) {
                    copy.store().var = joinStore.var;
                    if (copy.jump() != null) copy.jump().label = joinJump.label;
                    else method.instructions.insert(copy.store(), new JumpInsnNode(Opcodes.GOTO, joinJump.label));
                }
                for (int index = joinJumpIndex; index >= joinIndex; index--)
                    method.instructions.remove(method.instructions.get(index));
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Composes a copy-only phi when its join has no terminal goto.  This is
     * the cleanup-tail form {@code source -> temp; join: temp -> final}.
     * Incoming branches write {@code final} directly, while a fall-through
     * predecessor that already owns {@code final} drops its redundant copy.
     * The join label is retained so protected-range and handler metadata are
     * not rewritten by this local-only cleanup.
     */
    static int removeCopyPhiJoins(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int joinIndex = 0; joinIndex < method.instructions.size(); joinIndex++) {
                if (!(method.instructions.get(joinIndex) instanceof LabelNode join)
                        // Preserve handler-entry stack semantics, but allow a
                        // protected range boundary when the label remains in
                        // place and only stack-empty local copies are removed.
                        || isHandlerLabel(method, join)) continue;
                int joinLoadIndex = nextExecutableIndex(method, joinIndex + 1);
                int joinStoreIndex = nextExecutableIndex(method, joinLoadIndex + 1);
                if (joinLoadIndex < 0 || joinStoreIndex < 0
                        || !(method.instructions.get(joinLoadIndex) instanceof VarInsnNode joinLoad)
                        || !(method.instructions.get(joinStoreIndex) instanceof VarInsnNode joinStore)
                        || !isLoad(joinLoad.getOpcode()) || !isStore(joinStore.getOpcode())
                        || joinLoad.var == joinStore.var
                        || loadOpcode(joinStore.getOpcode()) != joinLoad.getOpcode()
                        ) continue;
                Set<Integer> joinReachable = normalReachableFromLabel(method, join,
                        instructionIndices(method));
                if (joinReachable.isEmpty()) continue;
                boolean unexpectedJoinLocalUse = false;
                for (int index : joinReachable) {
                    AbstractInsnNode instruction = method.instructions.get(index);
                    if (!(instruction instanceof VarInsnNode variable) || variable.var != joinLoad.var) continue;
                    if (instruction != joinLoad && (isLoad(variable.getOpcode()) || isStore(variable.getOpcode()))) {
                        unexpectedJoinLocalUse = true;
                        break;
                    }
                }
                if (unexpectedJoinLocalUse) continue;

                List<JoinCopy> incoming = new ArrayList<>();
                boolean invalid = false;
                for (int index = 0; index < method.instructions.size(); index++) {
                    AbstractInsnNode instruction = method.instructions.get(index);
                    if (instruction instanceof JumpInsnNode jump && jump.label == join) {
                        if (jump.getOpcode() != Opcodes.GOTO) {
                            invalid = true;
                            break;
                        }
                        int storeIndex = previousExecutableIndex(method, index - 1);
                        int loadIndex = previousExecutableIndex(method, storeIndex - 1);
                        if (storeIndex < 0 || loadIndex < 0
                                || !(method.instructions.get(storeIndex) instanceof VarInsnNode store)
                                || !(method.instructions.get(loadIndex) instanceof VarInsnNode load)
                                || !isCopyPair(load, store, joinLoad)
                                || !sameProtectedProfile(method, load, joinLoad)
                                || !sameProtectedProfile(method, store, joinStore)) {
                            invalid = true;
                            break;
                        }
                        incoming.add(new JoinCopy(load, store, jump, false));
                    }
                    if (instruction instanceof TableSwitchInsnNode table
                            && (table.dflt == join || table.labels.contains(join))) invalid = true;
                    if (instruction instanceof LookupSwitchInsnNode lookup
                            && (lookup.dflt == join || lookup.labels.contains(join))) invalid = true;
                }
                if (invalid) continue;

                int fallthroughStoreIndex = previousExecutableIndex(method, joinIndex - 1);
                int fallthroughLoadIndex = previousExecutableIndex(method, fallthroughStoreIndex - 1);
                JoinCopy fallthrough = null;
                if (fallthroughStoreIndex >= 0 && fallthroughLoadIndex >= 0
                        && method.instructions.get(fallthroughStoreIndex) instanceof VarInsnNode fallthroughStore
                        && method.instructions.get(fallthroughLoadIndex) instanceof VarInsnNode fallthroughLoad
                        && fallthroughStore.var == joinLoad.var
                        && fallthroughLoad.getOpcode() == joinLoad.getOpcode()
                        && fallthroughStore.getOpcode() == joinStore.getOpcode()
                        && sameProtectedProfile(method, fallthroughLoad, joinLoad)
                        && sameProtectedProfile(method, fallthroughStore, joinStore)) {
                    int sourceLabel = previousLabelIndex(method, fallthroughLoadIndex);
                    if (sourceLabel < 0 || !isRangeBoundary(method, (LabelNode) method.instructions.get(sourceLabel))
                            && !hasNonGotoIncomingEdge(method, (LabelNode) method.instructions.get(sourceLabel))) {
                        fallthrough = new JoinCopy(fallthroughLoad, fallthroughStore, null,
                                fallthroughLoad.var == joinStore.var);
                    }
                }
                if (fallthrough != null) incoming.add(fallthrough);
                if (incoming.size() < 2) continue;

                for (JoinCopy copy : incoming) {
                    if (copy.fallthrough()) {
                        method.instructions.remove(copy.load());
                        method.instructions.remove(copy.store());
                    } else {
                        copy.store().var = joinStore.var;
                    }
                }
                method.instructions.remove(joinLoad);
                method.instructions.remove(joinStore);
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Merges split protected ranges only when their handler and catch type
     * are identical and the gap cannot throw.  DEX block boundaries often
     * split one logical catch-all scope around a branch or copy-only block;
     * leaving those ranges separate makes CFR reconstruct a separate catch
     * continuation for each fragment.  The proof refuses calls, field/type
     * operations, array operations, monitor operations, arithmetic faults,
     * competing handler coverage, and handler labels in the gap.
     */
    static int mergeEquivalentHandlerRanges(MethodNode method) {
        int merged = 0;
        boolean changed;
        do {
            changed = false;
            // Handler-table order is semantic priority, while range
            // coalescing needs to discover the source-order outer fragment
            // before an inner handler has been moved ahead of it.
            List<TryCatchBlockNode> sourceOrder = new ArrayList<>(method.tryCatchBlocks);
            sourceOrder.sort(Comparator.comparingInt(range -> method.instructions.indexOf(range.start)));
            for (int firstIndex = 0; firstIndex < sourceOrder.size(); firstIndex++) {
                TryCatchBlockNode first = sourceOrder.get(firstIndex);
                if (!method.tryCatchBlocks.contains(first)) continue;
                int firstStart = method.instructions.indexOf(first.start);
                int firstEnd = method.instructions.indexOf(first.end);
                for (int secondIndex = firstIndex + 1; secondIndex < sourceOrder.size(); secondIndex++) {
                    TryCatchBlockNode second = sourceOrder.get(secondIndex);
                    if (!method.tryCatchBlocks.contains(second)) continue;
                    if (first.handler != second.handler || !java.util.Objects.equals(first.type, second.type)) continue;
                    int secondStart = method.instructions.indexOf(second.start);
                    int secondEnd = method.instructions.indexOf(second.end);
                    if (firstStart < 0 || firstEnd < 0 || secondStart < 0 || secondEnd < 0
                            || firstEnd > secondStart) continue;
                    if (!nonThrowingUncoveredGap(method, first, second, firstEnd, secondStart)
                            && !nestedProtectedGap(method, first, second, firstEnd, secondStart)) {
                        int closureEnd = nestedProtectedClosureEnd(method, first, second, firstEnd, secondEnd);
                        if (closureEnd < 0) continue;
                        TryCatchBlockNode endRange = method.tryCatchBlocks.stream()
                                .filter(range -> method.instructions.indexOf(range.end) == closureEnd)
                                .findFirst().orElse(null);
                        if (endRange == null) continue;
                        first.end = endRange.end;
                        method.tryCatchBlocks.remove(second);
                        preserveNestedHandlerPriority(method, first, firstStart, closureEnd);
                        merged++;
                        changed = true;
                        break;
                    }
                    first.end = second.end;
                    method.tryCatchBlocks.remove(second);
                    preserveNestedHandlerPriority(method, first, firstStart, secondEnd);
                    merged++;
                    changed = true;
                    break;
                }
                if (changed) break;
            }
        } while (changed);
        return merged;
    }

    /**
     * Allows an outer catch-all range to bridge DEX fragments around nested
     * protected ranges.  Every throwing instruction in the gap must already
     * be covered by a range fully contained by the prospective merged range;
     * handler labels and external branch entries remain hard boundaries.
     */
    private static boolean nestedProtectedGap(MethodNode method,
                                               TryCatchBlockNode first,
                                               TryCatchBlockNode second,
                                               int start,
                                               int end) {
        if (first.type != null || second.type != null) return false;
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            if (range == first || range == second) continue;
            int rangeStart = method.instructions.indexOf(range.start);
            int rangeEnd = method.instructions.indexOf(range.end);
            if (rangeStart < end && rangeEnd > start
                    && !(method.instructions.indexOf(first.start) <= rangeStart
                    && rangeEnd <= method.instructions.indexOf(second.end))) return false;
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump) {
                int source = method.instructions.indexOf(instruction);
                int target = method.instructions.indexOf(jump.label);
                if (target >= start && target < end && (source < start || source >= end)) return false;
            }
        }
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode label && isHandlerLabel(method, label)) return false;
            if (!isExecutableInstruction(instruction) || isNonThrowing(instruction)) continue;
            boolean covered = false;
            for (TryCatchBlockNode range : method.tryCatchBlocks) {
                if (range == first || range == second) continue;
                int rangeStart = method.instructions.indexOf(range.start);
                int rangeEnd = method.instructions.indexOf(range.end);
                if (rangeStart <= index && index < rangeEnd) {
                    covered = true;
                    break;
                }
            }
            if (!covered) return false;
        }
        return true;
    }

    /**
     * Computes a contained-range closure for adjacent fragments whose nested
     * protected range ends after the second fragment.  This is the shape
     * produced when two DEX cleanup stages interleave: the outer range can be
     * widened to the nested range's end only if every instruction in the
     * widened interval remains covered by an existing range.
     */
    private static int nestedProtectedClosureEnd(MethodNode method,
                                                  TryCatchBlockNode first,
                                                  TryCatchBlockNode second,
                                                  int start,
                                                  int end) {
        int closure = end;
        boolean changed;
        do {
            changed = false;
            for (TryCatchBlockNode range : method.tryCatchBlocks) {
                if (range == first || range == second) continue;
                int rangeStart = method.instructions.indexOf(range.start);
                int rangeEnd = method.instructions.indexOf(range.end);
                if (rangeStart < start && rangeEnd > start) return -1;
                if (rangeStart >= start && rangeStart < closure && rangeEnd > closure) {
                    closure = rangeEnd;
                    changed = true;
                }
            }
        } while (changed);

        for (int index = start; index < closure; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode label && isHandlerLabel(method, label)) return -1;
            if (!isExecutableInstruction(instruction) || isNonThrowing(instruction)) continue;
            boolean covered = false;
            for (TryCatchBlockNode range : method.tryCatchBlocks) {
                int rangeStart = method.instructions.indexOf(range.start);
                int rangeEnd = method.instructions.indexOf(range.end);
                if (rangeStart <= index && index < rangeEnd) {
                    covered = true;
                    break;
                }
            }
            if (!covered) return -1;
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof JumpInsnNode jump)) continue;
            int source = method.instructions.indexOf(instruction);
            int target = method.instructions.indexOf(jump.label);
            if (target >= start && target < closure && (source < start || source >= closure)) return -1;
        }
        return closure;
    }

    /**
     * A merged catch-all is an outer resource handler.  JVM exception-table
     * order is significant, so nested close/suppression handlers must remain
     * ahead of it after the merge or the outer catch would intercept their
     * cleanup exceptions first.
     */
    private static void preserveNestedHandlerPriority(MethodNode method,
                                                       TryCatchBlockNode merged,
                                                       int mergedStart,
                                                       int mergedEnd) {
        int mergedIndex = method.tryCatchBlocks.indexOf(merged);
        if (mergedIndex < 0) return;
        int insertion = mergedIndex;
        for (int index = 0; index < method.tryCatchBlocks.size(); index++) {
            TryCatchBlockNode candidate = method.tryCatchBlocks.get(index);
            if (candidate == merged) continue;
            int candidateStart = method.instructions.indexOf(candidate.start);
            int candidateEnd = method.instructions.indexOf(candidate.end);
            if (candidateStart >= mergedStart && candidateEnd <= mergedEnd)
                insertion = Math.max(insertion, index);
        }
        if (insertion <= mergedIndex) return;
        method.tryCatchBlocks.remove(mergedIndex);
        int adjusted = Math.min(insertion, method.tryCatchBlocks.size());
        method.tryCatchBlocks.add(adjusted, merged);
    }

    private static boolean nonThrowingUncoveredGap(MethodNode method,
                                                     TryCatchBlockNode first,
                                                     TryCatchBlockNode second,
                                                     int start,
                                                     int end) {
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (!isNonThrowing(instruction)) return false;
            if (instruction instanceof LabelNode label && method.tryCatchBlocks.stream()
                    .anyMatch(range -> range != first && range != second && range.handler == label)) return false;
            if (!isExecutableInstruction(instruction)) continue;
            for (TryCatchBlockNode range : method.tryCatchBlocks) {
                if (range == first || range == second) continue;
                int rangeStart = method.instructions.indexOf(range.start);
                int rangeEnd = method.instructions.indexOf(range.end);
                if (rangeStart <= index && index < rangeEnd
                        && (range.handler != first.handler || !java.util.Objects.equals(range.type, first.type)))
                    return false;
            }
        }
        return true;
    }

    private static boolean isNonThrowing(AbstractInsnNode instruction) {
        int type = instruction.getType();
        if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.FRAME
                || type == AbstractInsnNode.LINE) return true;
        if (instruction instanceof FieldInsnNode || instruction instanceof MethodInsnNode
                || instruction instanceof InvokeDynamicInsnNode || instruction instanceof TypeInsnNode
                || instruction instanceof MultiANewArrayInsnNode) return false;
        int opcode = instruction.getOpcode();
        return switch (opcode) {
            case Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.DALOAD,
                    Opcodes.FALOAD, Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.SALOAD,
                    Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.DASTORE,
                    Opcodes.FASTORE, Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.SASTORE,
                    Opcodes.ARRAYLENGTH, Opcodes.ATHROW, Opcodes.MONITORENTER, Opcodes.MONITOREXIT,
                    Opcodes.IDIV, Opcodes.IREM, Opcodes.LDIV, Opcodes.LREM,
                    Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> false;
            default -> true;
        };
    }

    private static boolean isExecutableInstruction(AbstractInsnNode instruction) {
        int type = instruction.getType();
        return type != AbstractInsnNode.LABEL && type != AbstractInsnNode.FRAME
                && type != AbstractInsnNode.LINE;
    }

    private record JoinCopy(VarInsnNode load, VarInsnNode store, JumpInsnNode jump, boolean fallthrough) {}

    private static boolean isCopyPair(VarInsnNode load, VarInsnNode store, VarInsnNode joinLoad) {
        return isLoad(load.getOpcode()) && isStore(store.getOpcode())
                && store.var == joinLoad.var && loadOpcode(store.getOpcode()) == load.getOpcode()
                && load.getOpcode() == joinLoad.getOpcode();
    }

    private static int previousLabelIndex(MethodNode method, int start) {
        for (int index = start; index >= 0; index--)
            if (method.instructions.get(index) instanceof LabelNode) return index;
        return -1;
    }

    private static boolean hasNonGotoIncomingEdge(MethodNode method, LabelNode label) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.label == label && jump.getOpcode() != Opcodes.GOTO)
                return true;
            if (instruction instanceof TableSwitchInsnNode table
                    && (table.dflt == label || table.labels.contains(label))) return true;
            if (instruction instanceof LookupSwitchInsnNode lookup
                    && (lookup.dflt == label || lookup.labels.contains(label))) return true;
        }
        return false;
    }

    /**
     * Removes repeated handler-local aliases when several handler stubs all
     * copy the same live source slot into one destination slot before entering
     * a common cleanup body.  The exception store at each handler entry is
     * retained.  Every destination load must be reachable from the common
     * target, and ASM's pre-edit frames must prove that the source slot has the
     * required category at each replacement site.
     */
    static int removeRepeatedHandlerCopyAliases(MethodNode method) {
        Map<LabelNode, List<HandlerCopy>> groups = new HashMap<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (!(instruction instanceof LabelNode handler) || !isHandlerLabel(method, handler)) continue;
            int nextLabel = nextLabelIndex(method, index + 1);
            List<Integer> executable = new ArrayList<>();
            for (int cursor = index + 1; cursor < method.instructions.size() && cursor < nextLabel; cursor++) {
                AbstractInsnNode candidate = method.instructions.get(cursor);
                int type = candidate.getType();
                if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.FRAME
                        || type == AbstractInsnNode.LINE) continue;
                executable.add(cursor);
            }
            if (executable.size() < 4) continue;
            int jumpIndex = executable.getLast();
            int storeIndex = executable.get(executable.size() - 2);
            int loadIndex = executable.get(executable.size() - 3);
            if (!(method.instructions.get(jumpIndex) instanceof JumpInsnNode jump)
                    || jump.getOpcode() != Opcodes.GOTO
                    || !(method.instructions.get(storeIndex) instanceof VarInsnNode store)
                    || !(method.instructions.get(loadIndex) instanceof VarInsnNode load)
                    || !isLoad(load.getOpcode()) || !isStore(store.getOpcode())
                    || loadOpcode(store.getOpcode()) != load.getOpcode()
                    || isRangeBoundary(method, jump.label)) continue;
            groups.computeIfAbsent(jump.label, ignored -> new ArrayList<>())
                    .add(new HandlerCopy(handler, load, store, jump));
        }

        int removed = 0;
        for (Map.Entry<LabelNode, List<HandlerCopy>> entry : groups.entrySet()) {
            List<HandlerCopy> copies = entry.getValue();
            if (copies.size() < 2) continue;
            HandlerCopy first = copies.getFirst();
            int sourceLocal = first.load().var;
            int destinationLocal = first.store().var;
            int sourceOpcode = first.load().getOpcode();
            int destinationOpcode = first.store().getOpcode();
            if (copies.stream().anyMatch(copy -> copy.load().var != sourceLocal
                    || copy.store().var != destinationLocal
                    || copy.load().getOpcode() != sourceOpcode
                    || copy.store().getOpcode() != destinationOpcode)) continue;

            Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
            for (int index = 0; index < method.instructions.size(); index++)
                indices.put(method.instructions.get(index), index);
            Set<Integer> reachable = normalReachableFromLabel(method, entry.getKey(), indices);
            if (reachable.isEmpty()) continue;
            Set<AbstractInsnNode> groupStores = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<AbstractInsnNode> groupLoads = Collections.newSetFromMap(new IdentityHashMap<>());
            for (HandlerCopy copy : copies) {
                groupStores.add(copy.store());
                groupLoads.add(copy.load());
            }
            boolean otherStore = false;
            boolean invalidSourceFlow = false;
            for (int index = 0; index < method.instructions.size(); index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                if (instruction instanceof VarInsnNode variable) {
                    if (variable.var == destinationLocal && isStore(variable.getOpcode())
                            && !groupStores.contains(variable)) otherStore = true;
                    if (variable.var == sourceLocal && isStore(variable.getOpcode())
                            && reachable.contains(index)) invalidSourceFlow = true;
                }
            }
            if (otherStore || invalidSourceFlow) continue;

            Frame<BasicValue>[] frames;
            try {
                frames = new Analyzer<BasicValue>(new BasicInterpreter()).analyze("Owner", method);
            } catch (AnalyzerException | RuntimeException ignored) {
                continue;
            }
            List<VarInsnNode> replacements = new ArrayList<>();
            boolean invalid = false;
            for (int index = 0; index < method.instructions.size(); index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                if (!(instruction instanceof VarInsnNode variable) || variable.var != destinationLocal
                        || !isLoad(variable.getOpcode()) || groupLoads.contains(variable)) continue;
                if (!reachable.contains(index)) {
                    invalid = true;
                    break;
                }
                Frame<BasicValue> frame = frames[index];
                if (frame == null || !validLocal(frame, sourceLocal, sourceOpcode)
                        || !validLocal(frame, destinationLocal, variable.getOpcode())) {
                    invalid = true;
                    break;
                }
                replacements.add(variable);
            }
            if (invalid) continue;
            for (VarInsnNode replacement : replacements)
                method.instructions.set(replacement, new VarInsnNode(sourceOpcode, sourceLocal));
            for (HandlerCopy copy : copies) {
                method.instructions.remove(copy.load());
                method.instructions.remove(copy.store());
            }
            removed += copies.size();
        }
        return removed;
    }

    /**
     * Retargets equivalent one-copy catch-all entries to one JVM handler
     * label.  This is intentionally a one-shot pass after the ordinary
     * materialization loop: the obsolete labels remain as unreachable bridge
     * code until a later dead-code pass, so range/frame iteration cannot be
     * invalidated while this method is still proving locals.
     */
    static int coalesceOneCopyHandlerRoutes(MethodNode method) {
        Map<HandlerRouteKey, List<HandlerRoute>> groups = new LinkedHashMap<>();
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            if (range.type != null) continue;
            HandlerRoute route = oneCopyHandlerRoute(method, range.handler);
            if (route == null || route.copyCount() == 0) continue;
            groups.computeIfAbsent(new HandlerRouteKey(route.anchor(), route.finalLocal(), route.destinations()),
                    ignored -> new ArrayList<>()).add(route);
        }
        return coalesceHandlerRouteGroups(method, groups);
    }

    /**
     * Retargets the narrower two-copy form of a catch-all route.  The
     * destination sequence is part of the key deliberately: two routes that
     * happen to end in the same local are not equivalent when they expose a
     * different intermediate materialization path to the verifier or to a
     * decompiler.
     */
    static int coalesceTwoCopyHandlerRoutes(MethodNode method) {
        Map<HandlerRouteKey, List<HandlerRoute>> groups = new LinkedHashMap<>();
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            if (range.type != null) continue;
            HandlerRoute route = oneCopyHandlerRoute(method, range.handler);
            if (route == null || route.copyCount() != 2) continue;
            groups.computeIfAbsent(new HandlerRouteKey(route.anchor(), route.finalLocal(), route.destinations()),
                    ignored -> new ArrayList<>()).add(route);
        }
        return coalesceHandlerRouteGroups(method, groups);
    }

    /**
     * Envelopes split protected fragments after handler routes have been
     * canonicalized. A close handler is the resource-layer proof anchor: all
     * fragments targeting the same copy route can share one protected range,
     * provided no unrelated range partially overlaps the envelope and no
     * external branch enters its interior. This is the bytecode counterpart
     * of composing nested cleanup layers; it intentionally does not merge
     * arbitrary catch-all ranges.
     */
    static int mergeResourceHandlerEnvelopes(MethodNode method) {
        int merged = 0;
        boolean changed;
        do {
            changed = false;
            Map<HandlerRouteKey, List<TryCatchBlockNode>> groups = new LinkedHashMap<>();
            for (TryCatchBlockNode range : method.tryCatchBlocks) {
                if (range.type != null) continue;
                HandlerRoute route = oneCopyHandlerRoute(method, range.handler);
                if (route == null || route.copyCount() == 0
                        || !containsCloseInvocation(method, route.anchor())) continue;
                groups.computeIfAbsent(new HandlerRouteKey(route.anchor(), route.finalLocal(), route.destinations()),
                        ignored -> new ArrayList<>()).add(range);
            }
            List<List<TryCatchBlockNode>> ordered = new ArrayList<>(groups.values());
            ordered.sort(Comparator.comparingInt((List<TryCatchBlockNode> ranges) ->
                    ranges.stream().mapToInt(range -> method.instructions.indexOf(range.end)).max().orElse(-1)
                    - ranges.stream().mapToInt(range -> method.instructions.indexOf(range.start)).min().orElse(-1))
                    .reversed());
            for (List<TryCatchBlockNode> ranges : ordered) {
                if (ranges.size() < 2) continue;
                int startIndex = ranges.stream().mapToInt(range -> method.instructions.indexOf(range.start)).min().orElse(-1);
                int endIndex = ranges.stream().mapToInt(range -> method.instructions.indexOf(range.end)).max().orElse(-1);
                if (startIndex < 0 || endIndex <= startIndex) continue;
                TryCatchBlockNode envelope = ranges.getFirst();
                if (!safeResourceEnvelope(method, ranges, startIndex, endIndex)) continue;
                LabelNode start = (LabelNode) method.instructions.get(startIndex);
                LabelNode end = (LabelNode) method.instructions.get(endIndex);
                envelope.start = start;
                envelope.end = end;
                for (TryCatchBlockNode range : ranges)
                    if (range != envelope) method.tryCatchBlocks.remove(range);
                preserveNestedHandlerPriority(method, envelope, startIndex, endIndex);
                merged += ranges.size() - 1;
                changed = true;
                break;
            }
        } while (changed);
        return merged;
    }

    /** Moves only proven resource-close stubs before their cleanup body. */
    static int relocateCloseHandlerStubs(MethodNode method) {
        Set<LabelNode> handlers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TryCatchBlockNode range : method.tryCatchBlocks)
            if (range.type == null) handlers.add(range.handler);
        int moved = 0;
        for (LabelNode handler : new ArrayList<>(handlers)) {
            HandlerRoute route = oneCopyHandlerRoute(method, handler);
            if (route == null || route.copyCount() != 1
                    || !containsCloseInvocation(method, route.anchor())) continue;
            int handlerIndex = method.instructions.indexOf(handler);
            int anchorIndex = method.instructions.indexOf(route.anchor());
            if (handlerIndex < 0 || anchorIndex < 0 || handlerIndex <= anchorIndex
                    || hasOrdinaryIncomingEdge(method, handler)
                    || hasStartOrEndBoundary(method, handler)
                    || !safeHandlerInsertionPoint(method, route.anchor())) continue;
            int end = handlerIndex + 1;
            while (end < method.instructions.size()
                    && !(method.instructions.get(end) instanceof LabelNode)) end++;
            List<AbstractInsnNode> stub = new ArrayList<>();
            boolean valid = true;
            for (int index = handlerIndex; index < end; index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                if (instruction.getType() == AbstractInsnNode.FRAME
                        || instruction.getType() == AbstractInsnNode.LINE
                        || instruction instanceof LabelNode
                        || instruction instanceof VarInsnNode variable
                        && (variable.getOpcode() == Opcodes.ASTORE || variable.getOpcode() == Opcodes.ALOAD)
                        || instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO)
                    stub.add(instruction);
                else valid = false;
            }
            if (!valid || stub.isEmpty()) continue;
            LabelNode skip = new LabelNode();
            for (AbstractInsnNode instruction : stub) method.instructions.remove(instruction);
            org.objectweb.asm.tree.InsnList insertion = new org.objectweb.asm.tree.InsnList();
            insertion.add(new JumpInsnNode(Opcodes.GOTO, skip));
            for (AbstractInsnNode instruction : stub) insertion.add(instruction);
            insertion.add(skip);
            method.instructions.insertBefore(route.anchor(), insertion);
            moved++;
        }
        return moved;
    }

    private static boolean hasOrdinaryIncomingEdge(MethodNode method, LabelNode label) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.label == label) return true;
            if (instruction instanceof TableSwitchInsnNode table
                    && (table.dflt == label || table.labels.contains(label))) return true;
            if (instruction instanceof LookupSwitchInsnNode lookup
                    && (lookup.dflt == label || lookup.labels.contains(label))) return true;
        }
        return false;
    }

    private static boolean safeHandlerInsertionPoint(MethodNode method, LabelNode anchor) {
        int index = method.instructions.indexOf(anchor);
        if (index < 0) return false;
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(range.start);
            int end = method.instructions.indexOf(range.end);
            if (start < 0 || end < 0) return false;
            if (range.start != anchor && start <= index && index < end) return false;
        }
        return true;
    }

    private static boolean containsCloseInvocation(MethodNode method, LabelNode anchor) {
        int start = method.instructions.indexOf(anchor);
        if (start < 0) return false;
        Set<LabelNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int index = start;
        while (index >= 0 && index < method.instructions.size()) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode label) {
                if (!visited.add(label)) return false;
                index++;
                continue;
            }
            if (instruction.getType() == AbstractInsnNode.FRAME
                    || instruction.getType() == AbstractInsnNode.LINE) {
                index++;
                continue;
            }
            if (instruction instanceof MethodInsnNode invoke
                    && invoke.name.equals("close") && invoke.desc.equals("()V")) return true;
            if (instruction instanceof VarInsnNode variable
                    && (variable.getOpcode() == Opcodes.ALOAD || variable.getOpcode() == Opcodes.ASTORE)) {
                index++;
                continue;
            }
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) {
                index = method.instructions.indexOf(jump.label);
                continue;
            }
            return false;
        }
        return false;
    }

    private static boolean safeResourceEnvelope(MethodNode method,
                                                List<TryCatchBlockNode> grouped,
                                                int start,
                                                int end) {
        Set<TryCatchBlockNode> group = Collections.newSetFromMap(new IdentityHashMap<>());
        group.addAll(grouped);
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            if (group.contains(range)) continue;
            int rangeStart = method.instructions.indexOf(range.start);
            int rangeEnd = method.instructions.indexOf(range.end);
            if (rangeStart < 0 || rangeEnd < 0) return false;
            boolean overlaps = rangeStart < end && rangeEnd > start;
            if (overlaps && !(start <= rangeStart && rangeEnd <= end)) return false;
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof JumpInsnNode jump)) continue;
            int source = method.instructions.indexOf(instruction);
            int target = method.instructions.indexOf(jump.label);
            if (target <= start || target >= end) continue;
            if (source < start || source >= end) return false;
        }
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode label && isHandlerLabel(method, label)
                    && !grouped.stream().anyMatch(range -> range.handler == label)) return false;
        }
        return true;
    }

    private static int coalesceHandlerRouteGroups(MethodNode method,
                                                   Map<HandlerRouteKey, List<HandlerRoute>> groups) {
        int retargeted = 0;
        List<HandlerRoute> duplicates = new ArrayList<>();
        for (List<HandlerRoute> routes : groups.values()) {
            if (routes.size() < 2) continue;
            HandlerRoute canonical = routes.stream()
                    .min(Comparator.comparingInt(route -> method.instructions.indexOf(route.handler())))
                    .orElseThrow();
            for (HandlerRoute duplicate : routes) {
                if (duplicate == canonical || hasBranchOrRangeBoundaryReference(method, duplicate.handler())) continue;
                boolean changed = false;
                for (TryCatchBlockNode range : method.tryCatchBlocks) {
                    if (range.handler == duplicate.handler()) {
                        range.handler = canonical.handler();
                        changed = true;
                    }
                }
                if (changed) {
                    retargeted++;
                    duplicates.add(duplicate);
                }
            }
        }
        for (HandlerRoute duplicate : duplicates) {
            if (hasNoExternalLabelReferences(method, duplicate.handler()))
                removeLabelBlock(method, duplicate.handler());
            for (LabelNode bridge : duplicate.bridges())
                if (hasNoExternalLabelReferences(method, bridge)) removeLabelBlock(method, bridge);
        }
        return retargeted;
    }

    private record HandlerRouteKey(LabelNode anchor, int finalLocal, List<Integer> destinations) {}

    private record HandlerRoute(LabelNode handler, LabelNode anchor, int finalLocal,
                                List<HandlerCopyStep> copySteps, List<Integer> destinations,
                                List<LabelNode> bridges) {
        private int copyCount() {
            return copySteps.size();
        }
    }

    private record HandlerCopyStep(VarInsnNode load, VarInsnNode store) {}

    /**
     * Collapses a route-local copy chain before handlers are coalesced.  DEX
     * exception lowering can introduce {@code exception -> temporary ->
     * canonicalException} even when the temporary has no consumer outside the
     * route.  Removing that dead middle slot makes otherwise equivalent
     * handler entries expose the same transfer state to the JVM and CFR.
     *
     * <p>The proof is deliberately local: both steps must be reference copies,
     * the intermediate slot must have exactly those two accesses in the whole
     * method, and the protected-range profile must be unchanged.  Thus this
     * cannot remove a value observed by a normal predecessor, a phi, or a
     * nested cleanup handler.</p>
     */
    static int collapseHandlerCopyChains(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            Set<LabelNode> handlers = Collections.newSetFromMap(new IdentityHashMap<>());
            for (TryCatchBlockNode range : method.tryCatchBlocks)
                if (range.type == null) handlers.add(range.handler);
            for (LabelNode handler : handlers) {
                HandlerRoute route = oneCopyHandlerRoute(method, handler);
                if (route == null || route.copySteps().size() < 2) continue;
                List<HandlerCopyStep> copies = route.copySteps();
                for (int index = 0; index + 1 < copies.size(); index++) {
                    HandlerCopyStep first = copies.get(index);
                    HandlerCopyStep second = copies.get(index + 1);
                    int intermediate = first.store().var;
                    boolean roundTrip = first.load().var == second.store().var;
                    if (intermediate == second.store().var
                            || intermediate != second.load().var
                            || first.load().getOpcode() != Opcodes.ALOAD
                            || first.store().getOpcode() != Opcodes.ASTORE
                            || second.load().getOpcode() != Opcodes.ALOAD
                            || second.store().getOpcode() != Opcodes.ASTORE
                            || !routeTemporaryIsUnobserved(method, first, second, intermediate)
                            || !handlerRouteCopyProfile(method, first, second)) continue;

                    // A route of the form
                    //
                    //   ALOAD exception; ASTORE temporary;
                    //   ALOAD temporary; ASTORE exception
                    //
                    // is a pure round trip.  Removing both pairs leaves the
                    // handler's original exception local initialized and
                    // preserves every label/range boundary on the route.
                    // The non-round-trip case still composes the two copies
                    // as before.
                    if (!roundTrip) second.load().var = first.load().var;
                    method.instructions.remove(first.load());
                    method.instructions.remove(first.store());
                    if (roundTrip) {
                        method.instructions.remove(second.load());
                        method.instructions.remove(second.store());
                        removed += 2;
                    }
                    removed += 2;
                    changed = true;
                    break;
                }
                if (changed) break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Proves that two adjacent copies belong to one handler-only route.  The
     * ordinary protected-profile check is intentionally stricter because it
     * protects normal expression motion.  Handler copy instructions cannot
     * throw and the route parser has already proved that no ordinary edge can
     * enter between them, so a range-label transition is safe here as long as
     * no handler label or observable instruction is crossed.
     */
    private static boolean handlerRouteCopyProfile(MethodNode method,
                                                   HandlerCopyStep first,
                                                   HandlerCopyStep second) {
        int firstIndex = method.instructions.indexOf(first.load());
        int secondIndex = method.instructions.indexOf(second.store());
        if (firstIndex < 0 || secondIndex <= firstIndex) return false;
        for (int index = firstIndex + 1; index < secondIndex; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode label && isHandlerLabel(method, label)) return false;
            if (instruction.getType() == AbstractInsnNode.LABEL
                    || instruction.getType() == AbstractInsnNode.FRAME
                    || instruction.getType() == AbstractInsnNode.LINE) continue;
            if (!(instruction instanceof VarInsnNode variable)
                    || (variable.getOpcode() != Opcodes.ALOAD && variable.getOpcode() != Opcodes.ASTORE)
                    || variable.var != first.store().var && variable != second.load()) return false;
        }
        return first.load().getOpcode() == second.load().getOpcode()
                && first.store().getOpcode() == second.store().getOpcode();
    }

    /**
     * The allocator may reuse a JVM slot in a different exceptional path, so
     * a whole-method access count is too conservative for a handler-only route.
     * This proof is path-local: the temporary is written/read only by this
     * copy pair on the route, and no external branch enters a label between
     * the pair.  Other handlers may reuse the slot without observing the
     * temporary removed here.
     */
    private static boolean routeTemporaryIsUnobserved(MethodNode method,
                                                      HandlerCopyStep first,
                                                      HandlerCopyStep second,
                                                      int temporary) {
        int firstIndex = method.instructions.indexOf(first.load());
        int secondIndex = method.instructions.indexOf(second.store());
        if (firstIndex < 0 || secondIndex <= firstIndex) return false;
        Set<LabelNode> internalLabels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int index = firstIndex; index <= secondIndex; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode label) internalLabels.add(label);
            if (instruction instanceof VarInsnNode variable && variable.var == temporary
                    && instruction != first.store() && instruction != second.load()) return false;
        }
        for (AbstractInsnNode instruction : method.instructions) {
            int source = method.instructions.indexOf(instruction);
            if (!(instruction instanceof JumpInsnNode jump) || !internalLabels.contains(jump.label)) continue;
            if (source < firstIndex || source > secondIndex) return false;
        }
        return true;
    }

    /**
     * Removes the first temporary used by a handler route when the exception
     * is copied immediately after entry and that temporary has no consumer
     * outside the route.  This is distinct from ordinary route-chain
     * collapsing: the initial ASTORE is the JVM handler's exception
     * materialization and must be retargeted, not deleted blindly.
     */
    static int collapseInitialHandlerCopyChains(MethodNode method) {
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            Set<LabelNode> handlers = Collections.newSetFromMap(new IdentityHashMap<>());
            for (TryCatchBlockNode range : method.tryCatchBlocks)
                if (range.type == null) handlers.add(range.handler);
            for (LabelNode handler : handlers) {
                int handlerIndex = method.instructions.indexOf(handler);
                int storeIndex = nextExecutableIndex(method, handlerIndex + 1);
                if (handlerIndex < 0 || storeIndex < 0
                        || !(method.instructions.get(storeIndex) instanceof VarInsnNode entryStore)
                        || entryStore.getOpcode() != Opcodes.ASTORE) continue;
                int jumpIndex = nextExecutableIndex(method, storeIndex + 1);
                if (jumpIndex < 0 || !(method.instructions.get(jumpIndex) instanceof JumpInsnNode jump)
                        || jump.getOpcode() != Opcodes.GOTO) continue;
                HandlerRoute route = oneCopyHandlerRoute(method, handler);
                if (route == null || route.copySteps().isEmpty()) continue;
                HandlerCopyStep first = route.copySteps().getFirst();
                if (first.load().getOpcode() != Opcodes.ALOAD
                        || first.store().getOpcode() != Opcodes.ASTORE
                        || first.load().var != entryStore.var
                        || countLocalAccesses(method, entryStore.var) != 2
                        || !sameProtectedProfile(method, entryStore, first.load())
                        || !sameProtectedProfile(method, entryStore, first.store())) continue;
                entryStore.var = first.store().var;
                method.instructions.remove(first.load());
                method.instructions.remove(first.store());
                removed += 2;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    private static @org.jetbrains.annotations.Nullable HandlerRoute oneCopyHandlerRoute(MethodNode method,
                                                                                          LabelNode handler) {
        if (handler == null || hasStartOrEndBoundary(method, handler)) return null;
        int handlerIndex = method.instructions.indexOf(handler);
        int storeIndex = nextExecutableIndex(method, handlerIndex + 1);
        if (handlerIndex < 0 || storeIndex < 0
                || !(method.instructions.get(storeIndex) instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE) return null;
        int jumpIndex = nextExecutableIndex(method, storeIndex + 1);
        if (jumpIndex < 0 || !(method.instructions.get(jumpIndex) instanceof JumpInsnNode jump)
                || jump.getOpcode() != Opcodes.GOTO) return null;

        LabelNode current = jump.label;
        int finalLocal = store.var;
        List<HandlerCopyStep> copySteps = new ArrayList<>();
        List<Integer> destinations = new ArrayList<>();
        List<LabelNode> bridges = new ArrayList<>();
        Set<LabelNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (visited.add(current)) {
            if (isHandlerLabel(method, current)) return null;
            int currentIndex = method.instructions.indexOf(current);
            if (currentIndex < 0) return null;
            int cursor = nextInstructionIndex(method, currentIndex + 1);
            if (cursor < 0) return null;
            while (true) {
                AbstractInsnNode instruction = method.instructions.get(cursor);
                if (instruction instanceof JumpInsnNode gotoNode && gotoNode.getOpcode() == Opcodes.GOTO) {
                    bridges.add(current);
                    current = gotoNode.label;
                    break;
                }
                if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD)
                    return new HandlerRoute(handler, current, finalLocal, List.copyOf(copySteps),
                            List.copyOf(destinations), List.copyOf(bridges));
                int copyIndex = nextInstructionIndex(method, cursor + 1);
                if (copyIndex < 0 || !(method.instructions.get(copyIndex) instanceof VarInsnNode copy)
                        || copy.getOpcode() != Opcodes.ASTORE)
                    return new HandlerRoute(handler, current, finalLocal, List.copyOf(copySteps),
                            List.copyOf(destinations), List.copyOf(bridges));
                finalLocal = copy.var;
                copySteps.add(new HandlerCopyStep(load, copy));
                destinations.add(copy.var);
                if (!bridges.contains(current)) bridges.add(current);
                int afterCopy = nextInstructionIndex(method, copyIndex + 1);
                if (afterCopy < 0) return null;
                AbstractInsnNode after = method.instructions.get(afterCopy);
                if (after instanceof VarInsnNode nextLoad && nextLoad.getOpcode() == Opcodes.ALOAD) {
                    cursor = afterCopy;
                    continue;
                }
                if (after instanceof JumpInsnNode gotoNode && gotoNode.getOpcode() == Opcodes.GOTO) {
                    current = gotoNode.label;
                    break;
                }
                if (after instanceof LabelNode label) {
                    current = label;
                    break;
                }
                return new HandlerRoute(handler, current, finalLocal, List.copyOf(copySteps),
                        List.copyOf(destinations), List.copyOf(bridges));
            }
        }
        return null;
    }

    private static boolean hasStartOrEndBoundary(MethodNode method, LabelNode label) {
        for (TryCatchBlockNode range : method.tryCatchBlocks)
            if (range.start == label || range.end == label) return true;
        return false;
    }

    private static int countLocalAccesses(MethodNode method, int local) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof VarInsnNode variable && variable.var == local) count++;
            if (instruction instanceof IincInsnNode increment && increment.var == local) count++;
        }
        return count;
    }

    private static boolean hasBranchOrRangeBoundaryReference(MethodNode method, LabelNode label) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.label == label) return true;
            if (instruction instanceof TableSwitchInsnNode table
                    && (table.dflt == label || table.labels.contains(label))) return true;
            if (instruction instanceof LookupSwitchInsnNode lookup
                    && (lookup.dflt == label || lookup.labels.contains(label))) return true;
        }
        return hasStartOrEndBoundary(method, label);
    }

    private static boolean hasNoExternalLabelReferences(MethodNode method, LabelNode label) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.label == label) return false;
            if (instruction instanceof TableSwitchInsnNode table
                    && (table.dflt == label || table.labels.contains(label))) return false;
            if (instruction instanceof LookupSwitchInsnNode lookup
                    && (lookup.dflt == label || lookup.labels.contains(label))) return false;
        }
        for (TryCatchBlockNode range : method.tryCatchBlocks)
            if (range.start == label || range.end == label || range.handler == label) return false;
        return true;
    }

    private static void removeLabelBlock(MethodNode method, LabelNode label) {
        int start = method.instructions.indexOf(label);
        if (start < 0) return;
        int end = start + 1;
        while (end < method.instructions.size() && !(method.instructions.get(end) instanceof LabelNode)) end++;
        for (int index = end - 1; index >= start; index--)
            method.instructions.remove(method.instructions.get(index));
    }

    private record HandlerCopy(LabelNode handler, VarInsnNode load, VarInsnNode store, JumpInsnNode jump) {}

    /**
     * Removes a proven one-use field-read temporary.  This is the bytecode
     * equivalent of re-emitting {@code this.field} at its sole consumer: the
     * field read and its receiver are moved only across metadata, field reads,
     * and local materialization instructions.  Calls, branches, labels, and
     * protected-boundary changes remain hard stops because moving a field read
     * across them could change evaluation or exception behavior.
     */
    static int removeOneUseFieldCopies(MethodNode method) {
        // A field read inside a dense handler layout can be observed through
        // several JVM entry frames even when the source local has one normal
        // consumer.  In composite resource/handler methods, retain the
        // original store for frame stability and only replace its consumer;
        // simple methods can remove the complete materialization pair.
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int storeIndex = 0; storeIndex < method.instructions.size(); storeIndex++) {
                AbstractInsnNode storeNode = method.instructions.get(storeIndex);
                if (!(storeNode instanceof VarInsnNode store) || !isStore(store.getOpcode())) continue;
                int fieldIndex = previousMetadataOnlyInstruction(method, storeIndex - 1);
                if (fieldIndex < 0 || !(method.instructions.get(fieldIndex) instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETFIELD && field.getOpcode() != Opcodes.GETSTATIC) continue;
                int receiverIndex = -1;
                AbstractInsnNode receiverNode = null;
                if (field.getOpcode() == Opcodes.GETFIELD) {
                    receiverIndex = previousMetadataOnlyInstruction(method, fieldIndex - 1);
                    if (receiverIndex < 0 || !(method.instructions.get(receiverIndex) instanceof VarInsnNode receiver)
                            || receiver.getOpcode() != Opcodes.ALOAD) continue;
                    receiverNode = receiver;
                }
                if (loadOpcode(store.getOpcode()) != fieldLoadOpcode(field.desc)) continue;
                int consumerIndex = firstCopyUse(method, storeIndex + 1, store.var);
                if (consumerIndex < 0) continue;
                AbstractInsnNode consumerNode = method.instructions.get(consumerIndex);
                if (!(consumerNode instanceof VarInsnNode consumer)
                        || consumer.getOpcode() != loadOpcode(store.getOpcode())) continue;
                if (!sameProtectedProfile(method, field, consumerNode)
                        || receiverNode != null && !sameProtectedProfile(method, receiverNode, consumerNode)) continue;
                if (isProtected(method, storeNode) && handlerUsesLocal(method, storeNode, store.var)) continue;
                if (receiverNode != null && sourceLocalMutated(method, receiverIndex + 1, consumerIndex,
                        ((VarInsnNode) receiverNode).var)) continue;
                if (!fieldCopyInterveningInstructions(method, storeIndex + 1, consumerIndex,
                        field, consumerNode)) continue;
                boolean hasLaterUse = hasAnyLocalRead(method, consumerIndex + 1, store.var);
                // In a split handler layout, preserve a field-backed local when
                // a frame or a later use still gives it semantic identity.  A
                // one-use preamble read with no frame boundary is safe to
                // re-emit at its immediate consumer, even when the method has
                // many unrelated protected ranges.  This removes aliases such
                // as `identity = this.identity` without changing handler
                // locals or moving a throwing field read.
                boolean preserveStore = hasLaterUse
                        || method.tryCatchBlocks.size() > 1 && hasFrameBetween(method, fieldIndex, consumerIndex);
                if (receiverNode != null)
                    method.instructions.insertBefore(consumerNode,
                            new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) receiverNode).var));
                method.instructions.insertBefore(consumerNode,
                        new FieldInsnNode(field.getOpcode(), field.owner, field.name, field.desc));
                method.instructions.remove(consumerNode);
                if (!preserveStore) {
                    method.instructions.remove(storeNode);
                    method.instructions.remove(field);
                    if (receiverNode != null) method.instructions.remove(receiverNode);
                }
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Removes the temporary used only to preserve a value across a field
     * assignment.  A common lowering shape is {@code value; ASTORE n; ALOAD
     * receiver; ALOAD n; PUTFIELD}.  When the value has no other use, the
     * stack can perform the assignment directly with {@code SWAP}; this is
     * the bytecode form of {@code this.field = producer()}.  The proof is
     * intentionally limited to a single protected region so existing frame
     * locals in composite handlers remain untouched.
     */
    static int removeOneUseFieldAssignmentLocals(MethodNode method) {
        if (method.tryCatchBlocks.size() > 1) return 0;
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int storeIndex = 0; storeIndex < method.instructions.size(); storeIndex++) {
                AbstractInsnNode storeNode = method.instructions.get(storeIndex);
                if (!(storeNode instanceof VarInsnNode store) || !isStore(store.getOpcode())) continue;
                int receiverIndex = nextMaterializationInstruction(method, storeIndex + 1);
                if (receiverIndex < 0 || !(method.instructions.get(receiverIndex) instanceof VarInsnNode receiver)
                        || receiver.getOpcode() != Opcodes.ALOAD) continue;
                int valueIndex = nextMaterializationInstruction(method, receiverIndex + 1);
                if (valueIndex < 0 || !(method.instructions.get(valueIndex) instanceof VarInsnNode valueLoad)
                        || valueLoad.var != store.var || valueLoad.getOpcode() != loadOpcode(store.getOpcode())) continue;
                int fieldIndex = nextMaterializationInstruction(method, valueIndex + 1);
                if (fieldIndex < 0 || !(method.instructions.get(fieldIndex) instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTFIELD && field.getOpcode() != Opcodes.PUTSTATIC) continue;
                if (loadOpcode(store.getOpcode()) != fieldLoadOpcode(field.desc)) continue;
                int producerIndex = previousMetadataOnlyInstruction(method, storeIndex - 1);
                if (producerIndex < 0) continue;
                AbstractInsnNode producer = method.instructions.get(producerIndex);
                if (!sameProtectedProfile(method, producer, field)
                        || !sameProtectedProfile(method, storeNode, field)) continue;
                if (isProtected(method, storeNode) && handlerUsesLocal(method, storeNode, store.var)) continue;
                if (hasAnyLocalRead(method, fieldIndex + 1, store.var)) continue;

                method.instructions.remove(storeNode);
                if (field.getOpcode() == Opcodes.PUTFIELD)
                    method.instructions.set(valueLoad, new InsnNode(Opcodes.SWAP));
                else
                    method.instructions.remove(valueLoad);
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    /**
     * Removes a dead field-read alias when the same field is read again on the
     * same straight-line path before any observable operation.  The second
     * read remains in place, so null/exception behavior is preserved while a
     * decompiler no longer has to represent the unused first read as a local.
     */
    static int removeRedundantFieldReadAliases(MethodNode method) {
        // Dense split-resource methods are the only layout where this proof
        // is currently useful enough to offset its frame risk.  Sparse
        // methods retain the established local-first materialization path.
        if ((method.tryCatchBlocks.size() > 0 && method.tryCatchBlocks.size() < 20)
                || containsMonitorRegion(method)) return 0;
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (int storeIndex = 0; storeIndex < method.instructions.size(); storeIndex++) {
                AbstractInsnNode storeNode = method.instructions.get(storeIndex);
                if (!(storeNode instanceof VarInsnNode store) || !isStore(store.getOpcode())) continue;
                int fieldIndex = previousMetadataOnlyInstruction(method, storeIndex - 1);
                if (fieldIndex < 0 || !(method.instructions.get(fieldIndex) instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETFIELD && field.getOpcode() != Opcodes.GETSTATIC) continue;
                if (loadOpcode(store.getOpcode()) != fieldLoadOpcode(field.desc)) continue;
                if (isProtected(method, storeNode) && handlerUsesLocal(method, storeNode, store.var)) continue;
                int receiverIndex = -1;
                int receiverLocal = -1;
                if (field.getOpcode() == Opcodes.GETFIELD) {
                    receiverIndex = previousMetadataOnlyInstruction(method, fieldIndex - 1);
                    if (receiverIndex < 0 || !(method.instructions.get(receiverIndex) instanceof VarInsnNode receiver)
                            || receiver.getOpcode() != Opcodes.ALOAD) continue;
                    receiverLocal = receiver.var;
                }
                int duplicateIndex = matchingFieldRead(method, storeIndex + 1, field, receiverLocal);
                if (duplicateIndex < 0) continue;
                AbstractInsnNode duplicate = method.instructions.get(duplicateIndex);
                if (!sameProtectedProfile(method, field, duplicate)) continue;
                if (receiverLocal >= 0 && sourceLocalMutated(method, receiverIndex + 1, duplicateIndex,
                        receiverLocal)) continue;
                if (!fieldCopyInterveningInstructions(method, storeIndex + 1, duplicateIndex,
                        field, duplicate)) continue;
                if (hasFrameBetween(method, storeIndex + 1, duplicateIndex)) continue;

                method.instructions.remove(storeNode);
                method.instructions.remove(field);
                if (receiverIndex >= 0) method.instructions.remove(method.instructions.get(receiverIndex));
                removed++;
                changed = true;
                break;
            }
        } while (changed);
        return removed;
    }

    private static int matchingFieldRead(MethodNode method, int start, FieldInsnNode expected,
                                         int receiverLocal) {
        for (int index = start; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() == AbstractInsnNode.LINE
                    || instruction.getType() == AbstractInsnNode.FRAME) continue;
            if (instruction.getType() == AbstractInsnNode.LABEL) {
                if (isUnreferencedFallthroughLabel(method, (LabelNode) instruction, index)) continue;
                return -1;
            }
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == expected.getOpcode()
                    && field.owner.equals(expected.owner)
                    && field.name.equals(expected.name)
                    && field.desc.equals(expected.desc)) {
                if (receiverLocal < 0) return index;
                int receiverIndex = previousMetadataOnlyInstruction(method, index - 1);
                if (receiverIndex >= 0 && method.instructions.get(receiverIndex) instanceof VarInsnNode receiver
                        && receiver.getOpcode() == Opcodes.ALOAD && receiver.var == receiverLocal)
                    return index;
                return -1;
            }
            if (instruction instanceof VarInsnNode || instruction instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)
                    || isConstantInstruction(instruction) || instruction.getOpcode() == Opcodes.NOP) continue;
            return -1;
        }
        return -1;
    }

    private static boolean hasFrameBetween(MethodNode method, int start, int end) {
        for (int index = start; index < end; index++)
            if (method.instructions.get(index).getType() == AbstractInsnNode.FRAME) return true;
        return false;
    }

    private static boolean containsMonitorRegion(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.MONITORENTER
                    || instruction.getOpcode() == Opcodes.MONITOREXIT) return true;
        }
        return false;
    }

    private static boolean hasAnyLocalRead(MethodNode method, int start, int local) {
        for (int index = start; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof VarInsnNode variable && variable.var == local
                    && (isLoad(variable.getOpcode()) || variable.getOpcode() == Opcodes.IINC))
                return true;
        }
        return false;
    }

    private static boolean hasAnyLocalAccess(MethodNode method, int start, int local) {
        for (int index = start; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (!(instruction instanceof VarInsnNode variable) || variable.var != local) continue;
            int opcode = variable.getOpcode();
            if (isLoad(opcode) || isStore(opcode) || opcode == Opcodes.IINC) return true;
        }
        return false;
    }

    private static boolean hasOtherLocalRead(MethodNode method, int local, AbstractInsnNode excluded) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction == excluded) continue;
            if (instruction instanceof VarInsnNode variable && variable.var == local
                    && (isLoad(variable.getOpcode()) || variable.getOpcode() == Opcodes.IINC)) return true;
        }
        return false;
    }

    /** Applies the independent proofs until no newly adjacent pair appears. */
    static int removeProvenMaterialization(MethodNode method) {
        return removeProvenMaterialization(method, false);
    }

    static int removeProvenMaterialization(MethodNode method, boolean aggressive) {
        int removed = 0;
        boolean changed;
        do {
            int copies = removeRedundantLocalCopies(method);
            int storeReloadStores = removeRedundantStoreReloadStores(method);
            int castStores = removeRedundantCastStores(method);
            // The invocation proof already checks protected profiles, handler
            // local use, and straight-line stack order.  Gating it on the
            // current try-catch count made it disappear precisely after the
            // authoritative exception layout had successfully coalesced a
            // dense DEX resource graph.  Aggressive cleanup is therefore
            // driven by the local proof, not by a range-count proxy.
            int invokeSlices = aggressive ? removeOneUseInvokeCopies(method) : 0;
            int bridges = aggressive ? removeUnconditionalCopyBridges(method) : 0;
            int singleBridges = aggressive ? removeSingleIncomingCopyBridges(method) : 0;
            int multiBridges = aggressive ? removeMultiSourceCopyBridges(method) : 0;
            int fallthroughBridges = aggressive ? removeFallthroughCopyJoins(method) : 0;
            int phiJoins = aggressive ? removeCopyPhiJoins(method) : 0;
            int mergedRanges = aggressive ? mergeEquivalentHandlerRanges(method) : 0;
            int handlerAliases = aggressive ? removeRepeatedHandlerCopyAliases(method) : 0;
            int pairs = removeDeadAdjacentPairs(method);
            int fields = removeOneUseFieldCopies(method);
            int fieldAssignments = removeOneUseFieldAssignmentLocals(method);
            int deadFieldAliases = removeRedundantFieldReadAliases(method);
            removed += copies + storeReloadStores + castStores + invokeSlices + bridges + singleBridges + multiBridges + fallthroughBridges + phiJoins + mergedRanges + handlerAliases
                    + pairs + fields + fieldAssignments + deadFieldAliases;
            changed = copies != 0 || storeReloadStores != 0 || castStores != 0 || bridges != 0 || singleBridges != 0
                    || multiBridges != 0
                    || fallthroughBridges != 0 || phiJoins != 0 || mergedRanges != 0 || handlerAliases != 0
                    || invokeSlices != 0 || pairs != 0 || fields != 0
                    || fieldAssignments != 0
                    || deadFieldAliases != 0;
        } while (changed);
        // The authoritative lowering plan may already have coalesced the DEX
        // fragments before this bytecode cleanup runs.  Requiring the
        // *pre-cleanup* range count here made the route proofs disappear
        // exactly for successfully planned nested-resource methods.  The
        // helpers below retain their own local/handler/range proofs, so the
        // aggressive policy can evaluate them from the finalized topology.
        if (aggressive) {
            removed += collapseInitialHandlerCopyChains(method);
            removed += collapseHandlerCopyChains(method);
            removed += coalesceOneCopyHandlerRoutes(method);
            removed += coalesceTwoCopyHandlerRoutes(method);
            // Route retargeting can make formerly distinct protected ranges
            // share one handler only after the route proof has completed.
            // Re-run the existing range proof on that authoritative topology
            // rather than widening ranges speculatively before the handlers
            // are known to be equivalent.
            removed += mergeEquivalentHandlerRanges(method);
            removed += mergeResourceHandlerEnvelopes(method);
            removed += relocateCloseHandlerStubs(method);
            // Relocation introduces a skip bridge by design.  Re-run only
            // the straight-line bridge proof afterward so transparent glue
            // created by the relocation itself does not become a decompiler
            // loop artifact.
            removed += removeUnconditionalCopyBridges(method);
        }
        return removed;
    }

    /**
     * Removes instruction padding that has no normal, branch, switch, or
     * exception-table entry path. DEX block termination can leave a run of
     * {@code NOP}/{@code ATHROW} instructions between independently reachable
     * lowering blocks. They are verifier-irrelevant but visible to decompilers
     * as artificial recovery labels. Handler labels and labels referenced by a
     * branch or a protected range are retained; this pass never redirects a
     * live edge or changes an exception range.
     */
    static int removeUnreachableInstructions(MethodNode method) {
        if (method.instructions.size() == 0) return 0;
        Map<LabelNode, Integer> labelIndices = new IdentityHashMap<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode label) labelIndices.put(label, index);
        }

        Set<LabelNode> referenced = referencedLabels(method);
        Set<Integer> reachable = new HashSet<>();
        ArrayDeque<Integer> work = new ArrayDeque<>();
        work.add(0);
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            Integer handler = labelIndices.get(range.handler);
            if (handler != null) work.add(handler);
        }

        while (!work.isEmpty()) {
            int index = nextReachableIndex(method, work.removeFirst());
            if (index < 0 || !reachable.add(index)) continue;
            AbstractInsnNode instruction = method.instructions.get(index);
            if (isTerminal(instruction)) continue;
            if (instruction instanceof JumpInsnNode jump) {
                Integer target = labelIndices.get(jump.label);
                if (target != null) work.add(target);
                if (jump.getOpcode() != Opcodes.GOTO) work.add(index + 1);
                continue;
            }
            if (instruction instanceof TableSwitchInsnNode table) {
                Integer target = labelIndices.get(table.dflt);
                if (target != null) work.add(target);
                for (LabelNode label : table.labels) {
                    target = labelIndices.get(label);
                    if (target != null) work.add(target);
                }
                continue;
            }
            if (instruction instanceof LookupSwitchInsnNode lookup) {
                Integer target = labelIndices.get(lookup.dflt);
                if (target != null) work.add(target);
                for (LabelNode label : lookup.labels) {
                    target = labelIndices.get(label);
                    if (target != null) work.add(target);
                }
                continue;
            }
            work.add(index + 1);
        }

        int removed = 0;
        for (int index = method.instructions.size() - 1; index >= 0; index--) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (reachable.contains(index)) continue;
            if (instruction instanceof LabelNode label && referenced.contains(label)) continue;
            method.instructions.remove(instruction);
            removed++;
        }
        return removed;
    }

    private static int nextReachableIndex(MethodNode method, int index) {
        while (index >= 0 && index < method.instructions.size()) {
            AbstractInsnNode instruction = method.instructions.get(index);
            int type = instruction.getType();
            if (type != AbstractInsnNode.LABEL && type != AbstractInsnNode.FRAME
                    && type != AbstractInsnNode.LINE) return index;
            index++;
        }
        return -1;
    }

    private static Set<LabelNode> referencedLabels(MethodNode method) {
        Set<LabelNode> labels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump) labels.add(jump.label);
            else if (instruction instanceof TableSwitchInsnNode table) {
                labels.add(table.dflt);
                labels.addAll(table.labels);
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                labels.add(lookup.dflt);
                labels.addAll(lookup.labels);
            }
        }
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            labels.add(range.start);
            labels.add(range.end);
            labels.add(range.handler);
        }
        return labels;
    }


    private static boolean fieldCopyInterveningInstructions(MethodNode method, int start, int end,
                                                             AbstractInsnNode producer,
                                                             AbstractInsnNode consumer) {
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() == AbstractInsnNode.LINE
                    || instruction.getType() == AbstractInsnNode.FRAME)
                continue;
            if (instruction.getType() == AbstractInsnNode.LABEL) {
                LabelNode label = (LabelNode) instruction;
                if (isUnreferencedFallthroughLabel(method, label, index)
                        || isProfileOnlyRangeLabel(method, label, index, producer, consumer)) continue;
                return false;
            }
            if (instruction instanceof VarInsnNode || instruction instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC))
                continue;
            if (isConstantInstruction(instruction)) continue;
            if (instruction.getOpcode() == Opcodes.NOP) continue;
            return false;
        }
        return true;
    }

    /**
     * A range-owned label can still be crossed by a local-only field proof
     * when it is not a branch/handler target, is not a frame anchor, and both
     * endpoints have the same complete protected-range profile.  The label is
     * retained; only the field read is re-emitted at its sole consumer.
     */
    private static boolean isProfileOnlyRangeLabel(MethodNode method, LabelNode label, int index,
                                                    AbstractInsnNode producer,
                                                    AbstractInsnNode consumer) {
        if (!isRangeBoundary(method, label) || isHandlerLabel(method, label)) return false;
        if (hasAnyIncomingEdge(method, label)) return false;
        for (int cursor = index + 1; cursor < method.instructions.size(); cursor++) {
            AbstractInsnNode next = method.instructions.get(cursor);
            if (next.getType() == AbstractInsnNode.LINE) continue;
            if (next.getType() == AbstractInsnNode.FRAME) return false;
            break;
        }
        return sameProtectedProfile(method, producer, consumer);
    }

    private static boolean hasAnyIncomingEdge(MethodNode method, LabelNode label) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.label == label) return true;
            if (instruction instanceof TableSwitchInsnNode table
                    && (table.dflt == label || table.labels.contains(label))) return true;
            if (instruction instanceof LookupSwitchInsnNode lookup
                    && (lookup.dflt == label || lookup.labels.contains(label))) return true;
        }
        return false;
    }

    private static boolean isConstantInstruction(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode) return true;
        if (instruction instanceof IntInsnNode intInsn)
            return intInsn.getOpcode() == Opcodes.BIPUSH || intInsn.getOpcode() == Opcodes.SIPUSH;
        int opcode = instruction.getOpcode();
        return opcode == Opcodes.ACONST_NULL
                || opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5
                || opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1
                || opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2
                || opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1;
    }

    private static int fieldLoadOpcode(String descriptor) {
        return switch (descriptor.charAt(0)) {
            case 'J' -> Opcodes.LLOAD;
            case 'D' -> Opcodes.DLOAD;
            case 'F' -> Opcodes.FLOAD;
            case 'L', '[' -> Opcodes.ALOAD;
            default -> Opcodes.ILOAD;
        };
    }

    /**
     * Returns the next instruction when only debug/frame metadata separates it
     * from the producer.  Only an unreferenced, frame-free fall-through label
     * may be crossed; a branch target or protected/handler label remains a
     * hard boundary.
     */
    private static int nextMaterializationInstruction(MethodNode method, int start) {
        for (int index = start; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() == AbstractInsnNode.LINE
                    || instruction.getType() == AbstractInsnNode.FRAME)
                continue;
            if (instruction.getType() == AbstractInsnNode.LABEL) {
                if (isUnreferencedFallthroughLabel(method, (LabelNode) instruction, index)) continue;
                return -1;
            }
            return index;
        }
        return -1;
    }

    private static int previousMetadataOnlyInstruction(MethodNode method, int start) {
        for (int index = start; index >= 0; index--) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getType() == AbstractInsnNode.LINE
                    || instruction.getType() == AbstractInsnNode.FRAME)
                continue;
            if (instruction.getType() == AbstractInsnNode.LABEL) {
                if (isUnreferencedFallthroughLabel(method, (LabelNode) instruction, index)) continue;
                return -1;
            }
            return index;
        }
        return -1;
    }

    private static int firstCopyUse(MethodNode method, int start, int local) {
        for (int index = start; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof VarInsnNode variable && variable.var == local) {
                int opcode = variable.getOpcode();
                if (isLoad(opcode) || opcode == Opcodes.IINC) return index;
                if (isStore(opcode)) return -1;
            }
            if (isControlFlowBoundary(method, index, instruction) || isTerminal(instruction)) return -1;
        }
        return -1;
    }

    private static boolean sourceLocalMutated(MethodNode method, int start, int end, int local) {
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof VarInsnNode variable && variable.var == local) {
                int opcode = variable.getOpcode();
                if (isStore(opcode) || opcode == Opcodes.IINC) return true;
            }
            if (isControlFlowBoundary(method, index, instruction) || isTerminal(instruction)) return true;
        }
        return false;
    }

    /**
     * Returns true when a normal path can read a local before assigning it
     * again.  This is a small forward dataflow used only for a store/reload
     * pair: all normal successors are followed, unconditional gotos are not
     * mistaken for fall-through, and loops remain conservative when they
     * revisit an instruction while the original value is still live.
     */
    private static boolean hasReadBeforeReassignment(MethodNode method, int start, int local) {
        Map<LabelNode, Integer> labels = new HashMap<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode label)
                labels.put(label, nextExecutableIndex(method, index + 1));
        }
        ArrayDeque<LocalFlow> work = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        work.add(new LocalFlow(nextExecutableIndex(method, start), true));
        while (!work.isEmpty()) {
            LocalFlow flow = work.removeFirst();
            int index = flow.index();
            if (index < 0) continue;
            long state = ((long) index << 1) | (flow.live() ? 1L : 0L);
            if (!visited.add(state)) {
                if (flow.live()) return true;
                continue;
            }
            boolean live = flow.live();
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof VarInsnNode variable && variable.var == local) {
                int opcode = variable.getOpcode();
                if (live && (isLoad(opcode) || opcode == Opcodes.IINC)) return true;
                if (isStore(opcode)) live = false;
            }
            if (isTerminal(instruction)) continue;
            if (instruction instanceof JumpInsnNode jump) {
                Integer target = labels.get(jump.label);
                if (target == null) return live;
                work.add(new LocalFlow(target, live));
                if (jump.getOpcode() == Opcodes.GOTO) continue;
                work.add(new LocalFlow(nextExecutableIndex(method, index + 1), live));
                continue;
            }
            if (instruction instanceof TableSwitchInsnNode table) {
                Integer defaultTarget = labels.get(table.dflt);
                if (defaultTarget == null) return live;
                work.add(new LocalFlow(defaultTarget, live));
                for (LabelNode label : table.labels) {
                    Integer target = labels.get(label);
                    if (target == null) return live;
                    work.add(new LocalFlow(target, live));
                }
                continue;
            }
            if (instruction instanceof LookupSwitchInsnNode lookup) {
                Integer defaultTarget = labels.get(lookup.dflt);
                if (defaultTarget == null) return live;
                work.add(new LocalFlow(defaultTarget, live));
                for (LabelNode label : lookup.labels) {
                    Integer target = labels.get(label);
                    if (target == null) return live;
                    work.add(new LocalFlow(target, live));
                }
                continue;
            }
            work.add(new LocalFlow(nextExecutableIndex(method, index + 1), live));
        }
        return false;
    }

    private record LocalFlow(int index, boolean live) {}

    private static int nextExecutableIndex(MethodNode method, int start) {
        for (int index = start; index < method.instructions.size(); index++) {
            int type = method.instructions.get(index).getType();
            if (type != AbstractInsnNode.LABEL && type != AbstractInsnNode.FRAME
                    && type != AbstractInsnNode.LINE) return index;
        }
        return -1;
    }

    private static int nextInstructionIndex(MethodNode method, int start) {
        for (int index = start; index < method.instructions.size(); index++) {
            int type = method.instructions.get(index).getType();
            if (type != AbstractInsnNode.FRAME && type != AbstractInsnNode.LINE) return index;
        }
        return -1;
    }

    private static int previousExecutableIndex(MethodNode method, int start) {
        for (int index = start; index >= 0; index--) {
            int type = method.instructions.get(index).getType();
            if (type != AbstractInsnNode.LABEL && type != AbstractInsnNode.FRAME
                    && type != AbstractInsnNode.LINE) return index;
        }
        return -1;
    }

    private static int nextLabelIndex(MethodNode method, int start) {
        for (int index = start; index < method.instructions.size(); index++)
            if (method.instructions.get(index) instanceof LabelNode) return index;
        return method.instructions.size();
    }

    private static boolean isHandlerLabel(MethodNode method, LabelNode label) {
        for (TryCatchBlockNode range : method.tryCatchBlocks)
            if (range.handler == label) return true;
        return false;
    }

    private static Set<Integer> normalReachableFromLabel(MethodNode method, LabelNode label,
                                                         Map<AbstractInsnNode, Integer> indices) {
        Integer start = indices.get(label);
        if (start == null) return Set.of();
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode target) {
                Integer index = indices.get(target);
                labels.put(target, index == null ? -1 : nextExecutableIndex(method, index + 1));
            }
        }
        Set<Integer> reachable = new HashSet<>();
        ArrayDeque<Integer> work = new ArrayDeque<>();
        work.add(nextExecutableIndex(method, start + 1));
        while (!work.isEmpty()) {
            int index = work.removeFirst();
            if (index < 0 || !reachable.add(index)) continue;
            AbstractInsnNode instruction = method.instructions.get(index);
            if (isTerminal(instruction)) continue;
            if (instruction instanceof JumpInsnNode jump) {
                Integer target = labels.get(jump.label);
                if (target == null || target < 0) continue;
                work.add(target);
                if (jump.getOpcode() == Opcodes.GOTO) continue;
                work.add(nextExecutableIndex(method, index + 1));
                continue;
            }
            if (instruction instanceof TableSwitchInsnNode table) {
                addReachableLabel(work, labels, table.dflt);
                for (LabelNode target : table.labels) addReachableLabel(work, labels, target);
                continue;
            }
            if (instruction instanceof LookupSwitchInsnNode lookup) {
                addReachableLabel(work, labels, lookup.dflt);
                for (LabelNode target : lookup.labels) addReachableLabel(work, labels, target);
                continue;
            }
            work.add(nextExecutableIndex(method, index + 1));
        }
        return reachable;
    }

    private static void addReachableLabel(ArrayDeque<Integer> work, Map<LabelNode, Integer> labels,
                                          LabelNode label) {
        Integer target = labels.get(label);
        if (target != null && target >= 0) work.add(target);
    }

    private static boolean validLocal(Frame<BasicValue> frame, int local, int opcode) {
        if (local < 0 || local >= frame.getLocals()) return false;
        BasicValue value = frame.getLocal(local);
        if (value == null || value == BasicValue.UNINITIALIZED_VALUE || value.getType() == null) return false;
        return switch (opcode) {
            case Opcodes.ALOAD -> value.getType().getSort() == Type.OBJECT || value.getType().getSort() == Type.ARRAY;
            case Opcodes.ILOAD -> value.getType().getSort() == Type.INT;
            case Opcodes.FLOAD -> value.getType().getSort() == Type.FLOAT;
            case Opcodes.LLOAD -> value.getType().getSort() == Type.LONG;
            case Opcodes.DLOAD -> value.getType().getSort() == Type.DOUBLE;
            default -> false;
        };
    }

    private static boolean isRangeBoundary(MethodNode method, LabelNode label) {
        for (TryCatchBlockNode range : method.tryCatchBlocks)
            if (range.start == label || range.end == label || range.handler == label) return true;
        return false;
    }

    /**
     * A later overwrite is not a use of the value produced by this pair, but
     * it is only safe to rely on that fact within one straight-line statement
     * sequence.  A referenced label or control-flow instruction can expose a
     * path that reads the old local before the overwrite, so those are hard
     * stops.
     */
    private static boolean hasLaterLiveUse(MethodNode method, int start, int local) {
        for (int index = start; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof VarInsnNode variable && variable.var == local) {
                int opcode = variable.getOpcode();
                if (isLoad(opcode) || opcode == Opcodes.IINC) return true;
                if (isStore(opcode)) return false;
            }
            if (isTerminal(instruction)) return false;
            if (isControlFlowBoundary(method, index, instruction)) return true;
        }
        return false;
    }

    private static boolean isControlFlowBoundary(MethodNode method, int index, AbstractInsnNode instruction) {
        int type = instruction.getType();
        if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.FRAME
                || type == AbstractInsnNode.LINE)
            return type == AbstractInsnNode.LABEL
                    && !isUnreferencedFallthroughLabel(method, (LabelNode) instruction, index);
        int opcode = instruction.getOpcode();
        return instruction instanceof org.objectweb.asm.tree.JumpInsnNode
                || instruction instanceof org.objectweb.asm.tree.TableSwitchInsnNode
                || instruction instanceof org.objectweb.asm.tree.LookupSwitchInsnNode
                || opcode == Opcodes.ATHROW
                || opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN
                || opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN;
    }

    /**
     * A compiler/debug label with no branch, handler, frame, or protected-range
     * ownership is only a fall-through marker.  It cannot expose another path
     * while a proven local materialization is being simplified.
     */
    private static boolean isUnreferencedFallthroughLabel(MethodNode method, LabelNode label, int index) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump && jump.label == label) return false;
            if (instruction instanceof TableSwitchInsnNode table
                    && (table.dflt == label || table.labels.contains(label))) return false;
            if (instruction instanceof LookupSwitchInsnNode lookup
                    && (lookup.dflt == label || lookup.labels.contains(label))) return false;
        }
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            if (range.start == label || range.end == label || range.handler == label) return false;
        }
        for (int cursor = index + 1; cursor < method.instructions.size(); cursor++) {
            AbstractInsnNode next = method.instructions.get(cursor);
            if (next.getType() == AbstractInsnNode.LINE) continue;
            return next.getType() != AbstractInsnNode.FRAME;
        }
        return true;
    }

    private static boolean isTerminal(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return opcode == Opcodes.ATHROW || opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN
                || opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN;
    }

    private static boolean isProtected(MethodNode method, AbstractInsnNode instruction) {
        int index = method.instructions.indexOf(instruction);
        for (var range : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(range.start);
            int end = method.instructions.indexOf(range.end);
            if (start <= index && index < end) return true;
        }
        return false;
    }

    private static boolean sameProtectedProfile(MethodNode method,
                                                 AbstractInsnNode first,
                                                 AbstractInsnNode second) {
        int firstIndex = method.instructions.indexOf(first);
        int secondIndex = method.instructions.indexOf(second);
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            boolean firstCovered = covered(method, range, firstIndex);
            boolean secondCovered = covered(method, range, secondIndex);
            if (firstCovered != secondCovered) return false;
        }
        return true;
    }

    private static boolean covered(MethodNode method, TryCatchBlockNode range, int index) {
        int start = method.instructions.indexOf(range.start);
        int end = method.instructions.indexOf(range.end);
        return start <= index && index < end;
    }

    /**
     * A protected producer may be removed only when no exception handler can
     * observe its local.  This conservative scan intentionally rejects a pair
     * if any handler-local path mentions the slot; it avoids changing handler
     * frame state while still allowing ordinary resource-body receiver aliases.
     */
    private static boolean handlerUsesLocal(MethodNode method,
                                             AbstractInsnNode protectedInstruction,
                                             int local) {
        int protectedIndex = method.instructions.indexOf(protectedInstruction);
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(range.start);
            int end = method.instructions.indexOf(range.end);
            if (!(start <= protectedIndex && protectedIndex < end)) continue;
            int handler = method.instructions.indexOf(range.handler);
            if (normalFlowReadsLocal(method, handler, local)) return true;
        }
        return false;
    }

    /** Checks only blocks normally reachable from a particular handler. */
    private static boolean normalFlowReadsLocal(MethodNode method, int handler, int local) {
        Map<LabelNode, Integer> labels = new HashMap<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode label)
                labels.put(label, nextExecutableIndex(method, index + 1));
        }
        ArrayDeque<Integer> work = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        work.add(nextExecutableIndex(method, handler + 1));
        while (!work.isEmpty()) {
            int index = work.removeFirst();
            if (index < 0 || !visited.add(index)) continue;
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof VarInsnNode variable && variable.var == local
                    && (isLoad(variable.getOpcode()) || variable.getOpcode() == Opcodes.IINC)) return true;
            if (isTerminal(instruction)) continue;
            if (instruction instanceof JumpInsnNode jump) {
                Integer target = labels.get(jump.label);
                if (target == null) return true;
                work.add(target);
                if (jump.getOpcode() == Opcodes.GOTO) continue;
                work.add(nextExecutableIndex(method, index + 1));
                continue;
            }
            if (instruction instanceof TableSwitchInsnNode table) {
                Integer target = labels.get(table.dflt);
                if (target == null) return true;
                work.add(target);
                for (LabelNode label : table.labels) {
                    target = labels.get(label);
                    if (target == null) return true;
                    work.add(target);
                }
                continue;
            }
            if (instruction instanceof LookupSwitchInsnNode lookup) {
                Integer target = labels.get(lookup.dflt);
                if (target == null) return true;
                work.add(target);
                for (LabelNode label : lookup.labels) {
                    target = labels.get(label);
                    if (target == null) return true;
                    work.add(target);
                }
                continue;
            }
            work.add(nextExecutableIndex(method, index + 1));
        }
        return false;
    }

    private static boolean isLoad(int opcode) {
        return opcode == Opcodes.ILOAD || opcode == Opcodes.LLOAD || opcode == Opcodes.FLOAD
                || opcode == Opcodes.DLOAD || opcode == Opcodes.ALOAD;
    }

    private static boolean isStore(int opcode) {
        return opcode == Opcodes.ISTORE || opcode == Opcodes.LSTORE || opcode == Opcodes.FSTORE
                || opcode == Opcodes.DSTORE || opcode == Opcodes.ASTORE;
    }

    private static int loadOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.ISTORE -> Opcodes.ILOAD;
            case Opcodes.LSTORE -> Opcodes.LLOAD;
            case Opcodes.FSTORE -> Opcodes.FLOAD;
            case Opcodes.DSTORE -> Opcodes.DLOAD;
            case Opcodes.ASTORE -> Opcodes.ALOAD;
            default -> -1;
        };
    }
}
