package me.darknet.dex.convert;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Performs the method-local structural checks that are useful before an
 * aggressive method is committed to its enclosing class.
 *
 * <p>This is deliberately stricter than the class verifier for local reads:
 * an exception handler local must be initialized on every path on which it is
 * read.  Detecting that at method staging time lets the caller discard only
 * the unsafe method and retain the rest of the class.</p>
 */
final class JvmMethodShapeValidator {
    private JvmMethodShapeValidator() {
    }

    static Validation validate(String owner, MethodNode method) {
        InsnList instructions = method.instructions;
        if (instructions == null || instructions.size() == 0)
            return Validation.failure("method has no emitted instructions");

        Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>();
        for (int i = 0; i < instructions.size(); i++)
            indices.put(instructions.get(i), i);

        Validation targets = validateBranchTargets(method, indices);
        if (!targets.valid()) return targets;

        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<BasicValue>(new BasicInterpreter()).analyze(owner, method);
        } catch (AnalyzerException | RuntimeException failure) {
            String location = failure instanceof AnalyzerException analyzer && analyzer.node != null
                    ? " at instruction " + indices.getOrDefault(analyzer.node, -1)
                    : "";
            return Validation.failure("method analyzer rejected emitted control flow: "
                    + describe(failure) + location);
        }

        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            Integer start = indices.get(range.start);
            Integer end = indices.get(range.end);
            Integer handler = indices.get(range.handler);
            if (start == null || end == null || handler == null)
                return Validation.failure("try-catch range references an absent label");
            if (start >= end)
                return Validation.failure("empty try-catch range");
            if (!containsExecutableInstruction(instructions, start, end))
                return Validation.failure("try-catch range contains no emitted instructions");
            if (frames[handler] == null)
                return Validation.failure("try-catch handler is unreachable");
            if (frames[handler].getStackSize() != 1)
                return Validation.failure("handler entry does not contain exactly one exception value");
            BasicValue exception = frames[handler].getStack(0);
            if (!initialized(exception) || !isReference(exception))
                return Validation.failure("handler entry exception value has an invalid category");
        }

        Validation loopRanges = validateLoopBoundaries(method, indices);
        if (!loopRanges.valid()) return loopRanges;

        Set<Integer> exceptionLocals = new HashSet<>();
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            Integer handler = indices.get(range.handler);
            if (handler == null) continue;
            for (int i = handler + 1; i < instructions.size(); i++) {
                AbstractInsnNode instruction = instructions.get(i);
                if (instruction.getType() == AbstractInsnNode.LABEL
                        || instruction.getType() == AbstractInsnNode.FRAME
                        || instruction.getType() == AbstractInsnNode.LINE)
                    continue;
                if (instruction instanceof VarInsnNode variable
                        && variable.getOpcode() == Opcodes.ASTORE)
                    exceptionLocals.add(variable.var);
                break;
            }
        }

        for (int i = 0; i < instructions.size(); i++) {
            AbstractInsnNode instruction = instructions.get(i);
            if (!(instruction instanceof VarInsnNode variable))
                continue;
            if (!isLoad(variable.getOpcode()) && variable.getOpcode() != Opcodes.IINC)
                continue;
            if (!exceptionLocals.contains(variable.var))
                continue;
            Frame<BasicValue> frame = frames[i];
            if (frame == null)
                continue; // unreachable code is not a runtime local read
            if (variable.var < 0 || variable.var >= frame.getLocals())
                return Validation.failure("local index " + variable.var + " is outside the frame");
            BasicValue value = frame.getLocal(variable.var);
            if (!initialized(value)) {
                return Validation.failure("local " + variable.var + " is read before initialization at instruction " + i
                        + " (" + instruction.getClass().getSimpleName() + ", exception locals="
                        + exceptionLocals + ")");
            }
            if (variable.getOpcode() != Opcodes.IINC && !matches(variable.getOpcode(), value))
                return Validation.failure("local " + variable.var + " has incompatible category for "
                        + opcodeName(variable.getOpcode()));
            if (variable.getOpcode() == Opcodes.IINC && value.getType() != null
                    && value.getType().getSort() != Type.INT)
                return Validation.failure("IINC reads a non-integer local " + variable.var);
        }
        return Validation.success();
    }

    /**
     * A protected range must not cut through a JVM back edge.  Such a range
     * can be verifier-valid while making the handler appear to terminate an
     * unconditional loop to structured decompilers.  This is a control-flow
     * invariant, rather than a heuristic based on the number of ranges.
     */
    private static Validation validateLoopBoundaries(MethodNode method,
                                                      Map<AbstractInsnNode, Integer> indices) {
        Set<Integer> normalReachable = normalReachable(method, indices);
        Set<AbstractInsnNode> exceptionOnlyBridges = exceptionOnlyBridgeJumps(method, indices);
        for (AbstractInsnNode instruction : method.instructions) {
            int source = indices.get(instruction);
            if (!normalReachable.contains(source)) continue;
            List<Integer> targets = new ArrayList<>();
            if (instruction instanceof JumpInsnNode jump) {
                if (exceptionOnlyBridges.contains(instruction)) continue;
                targets.add(indices.getOrDefault(jump.label, -1));
            } else if (instruction instanceof TableSwitchInsnNode table) {
                addTargets(indices, targets, table.dflt, table.labels);
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                addTargets(indices, targets, lookup.dflt, lookup.labels);
            }
            for (int target : targets) {
                if (target < 0 || target >= source || !normalReachable.contains(target)) continue;
                for (TryCatchBlockNode range : method.tryCatchBlocks) {
                    int start = indices.get(range.start);
                    int end = indices.get(range.end);
                    boolean sourceInside = start <= source && source < end;
                    boolean targetInside = start <= target && target < end;
                    if (sourceInside != targetInside)
                        return Validation.failure("protected range cuts through a JVM back edge"
                                + " (range=" + start + ".." + end
                                + ", edge=" + source + "->" + target + ")");
                }
            }
        }
        return Validation.success();
    }

    /**
     * A handler entry may be emitted as a JVM stack-to-local bridge followed
     * by an unconditional transfer into the shared handler/cleanup body.  Its
     * backward transfer is not a source loop edge.  Recognize only bridges
     * that have no ordinary incoming branch and contain stores/loads only
     * before the final GOTO; direct handlers with normal predecessors remain
     * subject to the loop-boundary check.
     */
    private static Set<AbstractInsnNode> exceptionOnlyBridgeJumps(MethodNode method,
                                                                   Map<AbstractInsnNode, Integer> indices) {
        Set<LabelNode> ordinaryTargets = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump) {
                ordinaryTargets.add(jump.label);
            } else if (instruction instanceof TableSwitchInsnNode table) {
                ordinaryTargets.add(table.dflt);
                ordinaryTargets.addAll(table.labels);
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                ordinaryTargets.add(lookup.dflt);
                ordinaryTargets.addAll(lookup.labels);
            }
        }

        Set<AbstractInsnNode> bridges = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (TryCatchBlockNode range : method.tryCatchBlocks) {
            if (ordinaryTargets.contains(range.handler)) continue;
            AbstractInsnNode instruction = nextExecutable(method, indices.get(range.handler));
            boolean consumedException = false;
            while (instruction instanceof VarInsnNode variable
                    && (isLoad(variable.getOpcode()) || isStore(variable.getOpcode()))) {
                consumedException |= variable.getOpcode() == Opcodes.ASTORE;
                instruction = nextExecutable(method, indices.get(instruction) + 1);
            }
            if (consumedException && instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO)
                bridges.add(instruction);
        }
        return bridges;
    }

    private static AbstractInsnNode nextExecutable(MethodNode method, Integer start) {
        if (start == null) return null;
        for (int index = start; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            int type = instruction.getType();
            if (type != AbstractInsnNode.LABEL && type != AbstractInsnNode.FRAME
                    && type != AbstractInsnNode.LINE)
                return instruction;
        }
        return null;
    }

    /**
     * Computes ordinary JVM control-flow reachability without adding implicit
     * exception-table edges.  Handler stubs are emitted after the normal exit
     * and are intentionally reached only through the exception table; treating
     * their backwards transfer as a source loop edge makes an otherwise valid
     * protected range look like it cuts through a loop.
     */
    private static Set<Integer> normalReachable(MethodNode method,
                                                Map<AbstractInsnNode, Integer> indices) {
        Set<Integer> reachable = new HashSet<>();
        ArrayDeque<AbstractInsnNode> work = new ArrayDeque<>();
        if (method.instructions.size() > 0)
            work.add(method.instructions.getFirst());
        while (!work.isEmpty()) {
            AbstractInsnNode instruction = work.removeFirst();
            Integer index = indices.get(instruction);
            if (index == null || !reachable.add(index)) continue;
            if (instruction instanceof JumpInsnNode jump) {
                addNormalTarget(work, jump.label);
                if (jump.getOpcode() != Opcodes.GOTO)
                    addNormalNext(work, method, index);
            } else if (instruction instanceof TableSwitchInsnNode table) {
                addNormalTarget(work, table.dflt);
                for (LabelNode label : table.labels) addNormalTarget(work, label);
            } else if (instruction instanceof LookupSwitchInsnNode lookup) {
                addNormalTarget(work, lookup.dflt);
                for (LabelNode label : lookup.labels) addNormalTarget(work, label);
            } else if (!isTerminal(instruction)) {
                addNormalNext(work, method, index);
            }
        }
        return reachable;
    }

    private static void addNormalTarget(ArrayDeque<AbstractInsnNode> work, LabelNode target) {
        if (target != null) work.add(target);
    }

    private static void addNormalNext(ArrayDeque<AbstractInsnNode> work, MethodNode method, int index) {
        if (index + 1 < method.instructions.size()) work.add(method.instructions.get(index + 1));
    }

    private static boolean isTerminal(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW;
    }


    private static Validation validateBranchTargets(MethodNode method,
                                                    Map<AbstractInsnNode, Integer> indices) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump
                    && !indices.containsKey(jump.label))
                return Validation.failure("branch target label was not emitted");
            if (instruction instanceof TableSwitchInsnNode table) {
                if (!indices.containsKey(table.dflt))
                    return Validation.failure("tableswitch default label was not emitted");
                for (LabelNode label : table.labels)
                    if (!indices.containsKey(label))
                        return Validation.failure("tableswitch target label was not emitted");
            }
            if (instruction instanceof LookupSwitchInsnNode lookup) {
                if (!indices.containsKey(lookup.dflt))
                    return Validation.failure("lookupswitch default label was not emitted");
                for (LabelNode label : lookup.labels)
                    if (!indices.containsKey(label))
                        return Validation.failure("lookupswitch target label was not emitted");
            }
        }
        return Validation.success();
    }

    private static void addTargets(Map<AbstractInsnNode, Integer> indices,
                                   List<Integer> output, LabelNode defaultTarget,
                                   List<LabelNode> targets) {
        output.add(indices.getOrDefault(defaultTarget, -1));
        for (LabelNode target : targets)
            output.add(indices.getOrDefault(target, -1));
    }

    private static boolean isLoad(int opcode) {
        return opcode == Opcodes.ILOAD || opcode == Opcodes.LLOAD || opcode == Opcodes.FLOAD
                || opcode == Opcodes.DLOAD || opcode == Opcodes.ALOAD;
    }

    private static boolean isStore(int opcode) {
        return opcode == Opcodes.ISTORE || opcode == Opcodes.LSTORE || opcode == Opcodes.FSTORE
                || opcode == Opcodes.DSTORE || opcode == Opcodes.ASTORE;
    }


    private static boolean initialized(BasicValue value) {
        return value != null && value != BasicValue.UNINITIALIZED_VALUE && value.getType() != null;
    }

    private static boolean isReference(BasicValue value) {
        if (!initialized(value)) return false;
        Type type = value.getType();
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private static boolean containsExecutableInstruction(InsnList instructions, int start, int end) {
        for (int i = start + 1; i < end; i++) {
            if (isExecutable(instructions.get(i))) return true;
        }
        return false;
    }

    private static boolean isExecutable(AbstractInsnNode instruction) {
        int type = instruction.getType();
        return type != AbstractInsnNode.LABEL
                && type != AbstractInsnNode.FRAME
                && type != AbstractInsnNode.LINE;
    }

    private static boolean matches(int opcode, BasicValue value) {
        Type type = value.getType();
        if (type == null) return false;
        return switch (opcode) {
            case Opcodes.ILOAD -> type.getSort() == Type.INT;
            case Opcodes.LLOAD -> type.getSort() == Type.LONG;
            case Opcodes.FLOAD -> type.getSort() == Type.FLOAT;
            case Opcodes.DLOAD -> type.getSort() == Type.DOUBLE;
            case Opcodes.ALOAD -> type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
            default -> true;
        };
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case Opcodes.ILOAD -> "ILOAD";
            case Opcodes.LLOAD -> "LLOAD";
            case Opcodes.FLOAD -> "FLOAD";
            case Opcodes.DLOAD -> "DLOAD";
            case Opcodes.ALOAD -> "ALOAD";
            default -> Integer.toString(opcode);
        };
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    record Validation(boolean valid, String reason) {
        static Validation success() {
            return new Validation(true, "");
        }

        static Validation failure(String reason) {
            return new Validation(false, reason);
        }
    }
}
