package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.IrExceptionHandler;
import me.darknet.dex.convert.ir.IrExceptionRegion;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrType;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovers lowering-only producer/consumer candidates without rewriting IR. */
final class JvmSingleUsePlanner {
	private JvmSingleUsePlanner() {
	}

	static @NotNull List<JvmSingleUseCandidate> discover(@NotNull IrMethod method,
	                                                     @NotNull LoweringUseGraph useGraph,
	                                                     @NotNull JvmOptimizationGuards guards) {
		List<JvmSingleUseCandidate> candidates = new ArrayList<>();
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				if (!(statement instanceof IrOp op) || op.canonical() != op) continue;
				if (op.payload() instanceof NewInstanceInstruction)
					addConstructorCandidates(candidates, method, useGraph, guards, op, block);
				if (useGraph.useCount(op) != 1) continue;
				LoweringUseGraph.UseSite site = useGraph.useSites(op).stream().findFirst().orElse(null);
				if (site == null || site.phi() || site.consumer() == null) continue;
				IrStmt consumer = site.consumer();
				if (consumer instanceof IrOp constructor && isConstructor(constructor)
						&& op.payload() instanceof NewInstanceInstruction) continue;
				addDirectCandidate(candidates, method, guards, op, block, site);
			}
		}
		addReceiverChainCandidates(candidates, method, useGraph, guards);
		addExpressionSliceCandidates(candidates, method, useGraph, guards);
		candidates.sort(Comparator.comparingInt(JvmSingleUseCandidate::sourceOffset)
				.thenComparing(candidate -> candidate.mode().ordinal()));
		return List.copyOf(candidates);
	}

	private static void addExpressionSliceCandidates(@NotNull List<JvmSingleUseCandidate> candidates,
	                                                  @NotNull IrMethod method,
	                                                  @NotNull LoweringUseGraph useGraph,
	                                                  @NotNull JvmOptimizationGuards guards) {
		Set<IrOp> seenTerminals = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				if (!(statement instanceof IrOp finalOperation) || finalOperation.canonical() != finalOperation
						|| useGraph.useCount(finalOperation) != 1 || !seenTerminals.add(finalOperation)) continue;
				LoweringUseGraph.UseSite site = useGraph.useSites(finalOperation).stream()
						.filter(candidate -> !candidate.phi() && candidate.consumer() != null)
						.findFirst().orElse(null);
				if (site == null) continue;
				List<IrOp> operations = expressionSlice(method, useGraph, guards, finalOperation, site.consumer());
				if (operations.size() < 2) continue;
				IrBlock producerBlock = blockContaining(method, operations.getFirst());
				IrBlock consumerBlock = site.block();
				if (producerBlock == null || consumerBlock == null) continue;
				List<IrBlock> glue = transparentPath(method, producerBlock, consumerBlock);
				boolean proof = expressionSliceProof(method, useGraph, guards, operations,
						site.consumer(), producerBlock, consumerBlock, glue);
				String reason = proof ? "" : expressionSliceRejectionReason(method, guards, operations,
						site.consumer(), producerBlock, consumerBlock, glue);
				candidates.add(candidate(method, operations.getFirst(), operations, site.consumer(),
						producerBlock, consumerBlock, glue, site.inputIndex(),
						JvmSingleUseCandidate.Mode.EXPRESSION_SLICE, proof, reason));
			}
		}
	}

	private static @NotNull List<IrOp> expressionSlice(@NotNull IrMethod method,
	                                                    @NotNull LoweringUseGraph useGraph,
	                                                    @NotNull JvmOptimizationGuards guards,
	                                                    @NotNull IrOp finalOperation,
	                                                    @NotNull IrStmt terminal) {
		List<IrOp> reverse = new ArrayList<>();
		Set<IrOp> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		IrOp current = finalOperation;
		while (visited.add(current)) {
			reverse.add(current);
			IrOp previous = null;
			for (IrValue input : current.inputs()) {
				if (!(input.canonical() instanceof IrOp candidate) || candidate == current
						|| useGraph.useCount(candidate) != 1
						|| useGraph.singleStatementConsumer(candidate) != current) continue;
				if (previous != null) return List.of();
				previous = candidate;
			}
			if (previous == null) break;
			current = previous;
		}
		java.util.Collections.reverse(reverse);
		return reverse;
	}

	private static boolean expressionSliceProof(@NotNull IrMethod method,
	                                            @NotNull LoweringUseGraph useGraph,
	                                            @NotNull JvmOptimizationGuards guards,
	                                            @NotNull List<IrOp> operations,
	                                            @NotNull IrStmt terminal,
	                                            @NotNull IrBlock producerBlock,
	                                            @NotNull IrBlock consumerBlock,
	                                            @NotNull List<IrBlock> glue) {
		if (!guards.safeStatement(terminal) || terminal instanceof IrTerminator terminator
				&& terminator.kind() != IrTerminatorKind.RETURN
				&& terminator.kind() != IrTerminatorKind.THROW) return false;
		for (IrOp operation : operations) {
			if (!guards.safeOperation(operation) || operation.isUnknown() || operation.isImprecise()
					|| !guards.sameAggressiveBoundary(operation, terminal)) return false;
			if (useGraph.useCount(operation) != 1) return false;
			for (IrValue input : operation.inputs()) {
				IrValue canonical = input.canonical();
				if (operations.stream().anyMatch(candidate -> candidate.canonical() == canonical)) continue;
				if (canonical instanceof IrUnknown || canonical.isImprecise()
						|| canonical.stackOnly() && !canonical.hasLocal()
						|| !(canonical instanceof IrConstant) && !canonical.hasLocal()) return false;
			}
		}
		for (int index = 0; index + 1 < operations.size(); index++)
			if (!expressionAdjacent(method, operations.get(index), operations.get(index + 1))) return false;
		return expressionAdjacent(method, operations.getLast(), terminal)
				&& !producerBlock.phis().stream().anyMatch(phi -> !phi.hasLocal())
				&& !consumerBlock.phis().stream().anyMatch(phi -> !phi.hasLocal())
				&& glue.stream().allMatch(JvmSingleUsePlanner::transparentGlue);
	}

	private static boolean expressionAdjacent(@NotNull IrMethod method,
	                                          @NotNull IrStmt first,
	                                          @NotNull IrStmt second) {
		IrBlock left = blockContaining(method, first);
		IrBlock right = blockContaining(method, second);
		if (left == null || right == null) return false;
		if (left == right) {
			int firstIndex = left.statements().indexOf(first);
			int secondIndex = second == left.terminator()
					? left.statements().size() : left.statements().indexOf(second);
			return firstIndex >= 0 && secondIndex == firstIndex + 1;
		}
		if (left.statements().indexOf(first) != left.statements().size() - 1
				|| left.terminator() == null || left.terminator().kind() != IrTerminatorKind.GOTO
				|| right.statements().indexOf(second) != 0) return false;
		// A slice may cross only transparent, single-successor glue.  Checking
		// the actual path here is important: merely seeing a GOTO at the end of
		// the producer block is insufficient when another statement-bearing block
		// sits between producer and consumer.
		return transparentPath(method, left, right).stream().allMatch(JvmSingleUsePlanner::transparentGlue)
				&& reaches(method, left, right);
	}

	private static boolean reaches(@NotNull IrMethod method, @NotNull IrBlock source, @NotNull IrBlock target) {
		Set<IrBlock> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		IrBlock current = source;
		while (current != target && visited.add(current)) {
			if (current.successors().size() != 1) return false;
			current = current.successors().getFirst();
		}
		return current == target;
	}

	private static @NotNull String expressionSliceRejectionReason(@NotNull IrMethod method,
	                                                               @NotNull JvmOptimizationGuards guards,
	                                                               @NotNull List<IrOp> operations,
	                                                               @NotNull IrStmt terminal,
	                                                               @NotNull IrBlock producerBlock,
	                                                               @NotNull IrBlock consumerBlock,
	                                                               @NotNull List<IrBlock> glue) {
		if (!guards.safeStatement(terminal)) return "expression-slice terminal semantics are incomplete";
		for (IrOp operation : operations) {
			if (!operation.semantics().complete()) return "expression-slice descriptor is incomplete";
			if (!guards.sameAggressiveBoundary(operation, terminal))
				return "expression slice crosses a resource or exception boundary";
		}
		if (!glue.stream().allMatch(JvmSingleUsePlanner::transparentGlue))
			return "expression slice crosses non-transparent CFG glue";
		return "expression-slice category, local, or evaluation-order proof failed";
	}

	private static void addReceiverChainCandidates(@NotNull List<JvmSingleUseCandidate> candidates,
	                                               @NotNull IrMethod method,
	                                               @NotNull LoweringUseGraph useGraph,
	                                               @NotNull JvmOptimizationGuards guards) {
		Set<IrOp> roots = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				if (!(statement instanceof IrOp op) || op.canonical() != op) continue;
				if (op.payload() instanceof NewInstanceInstruction) {
					IrOp constructor = immediateConstructor(method, block, op);
					if (constructor != null && firstConstructedReceiverUse(method, block, op, constructor) != null)
						roots.add(op);
				} else if (isReceiverReturningInvoke(op)
						&& !(op.inputs().isEmpty() || op.inputs().getFirst().canonical() instanceof IrOp input
						&& (isReceiverReturningInvoke(input) || input.payload() instanceof NewInstanceInstruction))) {
					roots.add(op);
				}
			}
		}
		for (IrOp root : roots) {
			JvmSingleUseCandidate candidate = receiverChainCandidate(method, useGraph, guards, root);
			if (candidate != null) candidates.add(candidate);
		}
	}

	private static @org.jetbrains.annotations.Nullable JvmSingleUseCandidate receiverChainCandidate(
			@NotNull IrMethod method, @NotNull LoweringUseGraph useGraph,
			@NotNull JvmOptimizationGuards guards, @NotNull IrOp root) {
		IrBlock producerBlock = blockContaining(method, root);
		if (producerBlock == null) return null;
		List<IrOp> operations = new ArrayList<>();
		IrOp current = root;
		IrOp constructor = null;
		if (root.payload() instanceof NewInstanceInstruction) {
			constructor = immediateConstructor(method, producerBlock, root);
			IrOp first = constructor == null ? null : firstConstructedReceiverUse(method, producerBlock, root, constructor);
			if (constructor == null || first == null) return null;
			operations.add(root);
			operations.add(constructor);
			current = first;
		} else {
			operations.add(root);
		}
		if (current != root) operations.add(current);

		Set<IrOp> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		visited.add(root);
		if (constructor != null) visited.add(constructor);
		visited.add(current);
		while (true) {
			IrStmt consumer = useGraph.singleStatementConsumer(current);
			if (consumer instanceof IrOp invoke && isReceiverReturningInvoke(invoke)
					&& !invoke.inputs().isEmpty()
					&& invoke.inputs().getFirst().canonical() == current) {
				if (!visited.add(invoke)) return null;
				operations.add(invoke);
				current = invoke;
				continue;
			}
			break;
		}
		if (operations.size() < 2) return null;
		IrStmt terminal = useGraph.singleStatementConsumer(current);
		if (terminal instanceof IrOp valueOperation && !isReceiverReturningInvoke(valueOperation)
				&& !isConstructor(valueOperation) && !ConversionSupport.isVoidType(valueOperation.type())
				&& !valueOperation.inputs().isEmpty()
				&& valueOperation.inputs().getFirst().canonical() == current.canonical()) {
			operations.add(valueOperation);
			current = valueOperation;
			terminal = useGraph.singleStatementConsumer(current);
		}
		if (terminal == null || terminal instanceof IrOp invoke && isReceiverReturningInvoke(invoke)) return null;
		IrBlock consumerBlock = blockContaining(method, terminal);
		if (consumerBlock == null) return null;
		int inputIndex = inputIndex(terminal, current);
		if (inputIndex < 0) return null;
		List<IrBlock> glue = transparentPath(method, producerBlock, consumerBlock);
		boolean proof = receiverChainProof(method, useGraph, guards, operations, terminal,
				producerBlock, consumerBlock, glue);
		String reason = proof ? "" : receiverChainRejectionReason(method, useGraph, guards, operations, terminal,
				producerBlock, consumerBlock, glue);
		return candidate(method, operations.getFirst(), operations, terminal, producerBlock, consumerBlock,
				glue, inputIndex, JvmSingleUseCandidate.Mode.RECEIVER_CHAIN, proof, reason);
	}

	private static @org.jetbrains.annotations.Nullable IrOp immediateConstructor(@NotNull IrMethod method,
	                                                                              @NotNull IrBlock block,
	                                                                              @NotNull IrOp allocation) {
		int index = block.statements().indexOf(allocation);
		if (index < 0) return null;
		for (int nextIndex = index + 1; nextIndex < block.statements().size(); nextIndex++) {
			IrStmt next = block.statements().get(nextIndex);
			if (next instanceof IrOp op && isConstructor(op)
					&& !op.inputs().isEmpty() && op.inputs().getFirst().canonical() == allocation) return op;
			return null;
		}
		IrBlock cursor = block;
		Set<IrBlock> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		while (visited.add(cursor) && cursor.terminator() != null
				&& cursor.terminator().kind() == IrTerminatorKind.GOTO
				&& cursor.successors().size() == 1) {
			IrBlock next = cursor.successors().getFirst();
			if (!next.statements().isEmpty()) {
				IrStmt first = next.statements().getFirst();
				return next.statements().size() == 1 && first instanceof IrOp op && isConstructor(op)
						&& !op.inputs().isEmpty() && op.inputs().getFirst().canonical() == allocation ? op : null;
			}
			cursor = next;
		}
		return null;
	}

	private static @org.jetbrains.annotations.Nullable IrOp firstConstructedReceiverUse(@NotNull IrMethod method,
	                                                                                     @NotNull IrBlock block,
	                                                                                     @NotNull IrOp allocation,
	                                                                                     @NotNull IrOp constructor) {
		IrBlock constructorBlock = blockContaining(method, constructor);
		if (constructorBlock == null) return null;
		int constructorIndex = constructorBlock.statements().indexOf(constructor);
		for (int index = constructorIndex + 1; index < constructorBlock.statements().size(); index++) {
			IrStmt statement = constructorBlock.statements().get(index);
			if (statement instanceof IrOp op && isReceiverReturningInvoke(op)
					&& !op.inputs().isEmpty() && op.inputs().getFirst().canonical() == allocation) return op;
			return null;
		}
		IrBlock cursor = constructorBlock;
		Set<IrBlock> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		while (visited.add(cursor) && cursor.terminator() != null
				&& cursor.terminator().kind() == IrTerminatorKind.GOTO
				&& cursor.successors().size() == 1) {
			IrBlock next = cursor.successors().getFirst();
			if (next.phis().isEmpty() && next.exceptionValue() == null) {
				for (IrStmt statement : next.statements()) {
					if (statement instanceof IrOp op && isReceiverReturningInvoke(op)
							&& !op.inputs().isEmpty() && op.inputs().getFirst().canonical() == allocation)
						return op;
					if (!(statement instanceof IrOp op) || op.canonical() != op)
						return null;
				}
			}
			cursor = next;
		}
		return null;
	}

	private static boolean receiverChainProof(@NotNull IrMethod method, @NotNull LoweringUseGraph useGraph,
	                                         @NotNull JvmOptimizationGuards guards, @NotNull List<IrOp> operations,
	                                         @NotNull IrStmt terminal, @NotNull IrBlock producerBlock,
	                                         @NotNull IrBlock consumerBlock, @NotNull List<IrBlock> glue) {
		if (!guards.safeStatement(terminal)) return false;
		if (terminal instanceof IrTerminator terminator && terminator.kind() != IrTerminatorKind.RETURN
				&& terminator.kind() != IrTerminatorKind.THROW) return false;
		if (producerBlock.exceptionValue() != null || consumerBlock.exceptionValue() != null
				|| !producerBlock.phis().isEmpty()
				|| consumerBlock.phis().stream().anyMatch(phi -> !phi.hasLocal())) {
			return false;
		}
		if (isLoopBlock(producerBlock) || isLoopBlock(consumerBlock)) {
			return false;
		}
		List<String> protectedProfile = protectedProfile(method, producerBlock);
		List<String> exceptionProfile = protectedHandlerProfile(method, producerBlock);
		Set<IrValue> chainValues = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrOp operation : operations)
			if (!isConstructor(operation)) chainValues.add(operation.canonical());
		for (IrOp operation : operations) {
			if ((isConstructor(operation) ? !guards.safeStatement(operation) : !guards.safeOperation(operation))
					|| !isConstructor(operation) && !knownReference(operation)) {
				return false;
			}
			if (!guards.sameAggressiveBoundary(operation, terminal)) return false;
			IrBlock block = blockContaining(method, operation);
			if (block == null || block.exceptionValue() != null || !block.phis().isEmpty()
					|| isLoopBlock(block) || !protectedProfile.equals(protectedProfile(method, block))
					|| !exceptionProfile.equals(protectedHandlerProfile(method, block))) {
				return false;
			}
			if (isConstructor(operation)) {
				if (useGraph.useCount(operation) != 0) return false;
			} else if (operation.payload() instanceof NewInstanceInstruction) {
				if (operation != operations.getFirst() || useGraph.useCount(operation) != 2) return false;
			} else if (useGraph.useCount(operation) != 1) return false;
			for (IrValue input : operation.inputs()) {
				IrValue canonical = input.canonical();
				if (chainValues.contains(canonical)) continue;
				if (canonical instanceof IrUnknown || canonical.isImprecise()
						|| !(canonical instanceof IrConstant) && !canonical.hasLocal())
					return false;
			}
		}
		IrValue previousValue = operations.getFirst();
		for (int index = 1; index < operations.size(); index++) {
			IrOp operation = operations.get(index);
			if (isConstructor(operation)) {
				if (operation.inputs().isEmpty() || operation.inputs().getFirst().canonical() != previousValue.canonical())
					return false;
			} else if (operation.inputs().isEmpty()
					|| operation.inputs().getFirst().canonical() != previousValue.canonical()
					|| operation != operations.getLast() && !isReceiverReturningInvoke(operation)) return false;
			if (!isConstructor(operation)) previousValue = operation;
		}
		if (!receiverLayoutProof(method, useGraph, guards, operations, terminal, producerBlock, consumerBlock)) return false;
		IrOp last = operations.getLast();
		boolean result = useGraph.useCount(last) == 1 && inputIndex(terminal, last) >= 0;
		return result;
	}

	private static boolean receiverLayoutProof(@NotNull IrMethod method, @NotNull LoweringUseGraph useGraph,
	                                           @NotNull JvmOptimizationGuards guards,
	                                           @NotNull List<IrOp> operations,
	                                           @NotNull IrStmt terminal, @NotNull IrBlock producerBlock,
	                                           @NotNull IrBlock consumerBlock) {
		for (int index = 0; index + 1 < operations.size(); index++) {
			IrBlock left = blockContaining(method, operations.get(index));
			IrBlock right = blockContaining(method, operations.get(index + 1));
			if (left == null || right == null || !receiverPathProof(method, useGraph, guards,
					operations, left, right)) return false;
		}
		IrOp last = operations.getLast();
		IrBlock lastBlock = blockContaining(method, last);
		if (lastBlock == null) return false;
		int lastIndex = lastBlock.statements().indexOf(last);
		if (lastBlock == consumerBlock) {
			int terminalIndex = terminal instanceof IrTerminator
					? lastBlock.statements().size() : lastBlock.statements().indexOf(terminal);
			return lastIndex >= 0 && terminalIndex == lastIndex + 1;
		}
		return lastIndex == lastBlock.statements().size() - 1
				&& lastBlock.terminator() != null
				&& lastBlock.terminator().kind() == IrTerminatorKind.GOTO
				&& (terminal instanceof IrTerminator
					? consumerBlock.statements().isEmpty()
					: consumerBlock.statements().getFirst() == terminal)
				&& receiverPathProof(method, useGraph, guards, operations, lastBlock, consumerBlock);
	}

	/**
	 * A fluent chain may be split by DEX blocks containing only the argument
	 * computation for the next receiver call.  Those computations are emitted
	 * recursively at the consuming call; they must be linear, single-use, and
	 * covered by the same exceptional profile as the chain itself.
	 */
	private static boolean receiverPathProof(@NotNull IrMethod method, @NotNull LoweringUseGraph useGraph,
	                                        @NotNull JvmOptimizationGuards guards, @NotNull List<IrOp> operations,
	                                        @NotNull IrBlock left, @NotNull IrBlock right) {
		if (left == right) {
			List<IrOp> localOperations = operations.stream()
					.filter(operation -> blockContaining(method, operation) == left).toList();
			for (int index = 0; index + 1 < localOperations.size(); index++) {
				int first = left.statements().indexOf(localOperations.get(index));
				int second = left.statements().indexOf(localOperations.get(index + 1));
				if (first < 0 || second <= first) return false;
				for (int statementIndex = first + 1; statementIndex < second; statementIndex++) {
					IrStmt statement = left.statements().get(statementIndex);
					if (statement instanceof IrOp op && op.canonical() == op
							&& op.payload() instanceof me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction
							&& !localOperations.contains(op)) return false;
				}
			}
			int leftIndex = left.statements().indexOf(localOperations.stream().findFirst().orElse(null));
			int rightIndex = left.statements().indexOf(localOperations.stream().skip(1).findFirst().orElse(null));
			return leftIndex >= 0 && (rightIndex < 0 || rightIndex > leftIndex);
		}
		IrBlock cursor = left;
		Set<IrBlock> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		List<String> profile = protectedHandlerProfile(method, left);
		while (cursor != right) {
			if (!visited.add(cursor) || cursor.successors().size() != 1) return false;
			IrBlock next = cursor.successors().getFirst();
			if (next != right && !receiverGlueBlock(method, useGraph, guards, operations, next, profile)) {
				return false;
			}
			cursor = next;
		}
		return protectedHandlerProfile(method, left).equals(protectedHandlerProfile(method, right));
	}

	private static boolean receiverGlueBlock(@NotNull IrMethod method, @NotNull LoweringUseGraph useGraph,
	                                         @NotNull JvmOptimizationGuards guards, @NotNull List<IrOp> operations,
	                                         @NotNull IrBlock block, @NotNull List<String> profile) {
		if (!block.phis().isEmpty() || block.exceptionValue() != null
				|| !protectedProfile(method, block).equals(protectedProfile(method,
					blockContaining(method, operations.getFirst())))
				|| !profile.equals(protectedHandlerProfile(method, block)))
			return false;
		if (block.terminator() == null || block.terminator().kind() != IrTerminatorKind.GOTO
				|| block.successors().size() != 1) return false;
		Set<IrOp> chain = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		chain.addAll(operations);
		for (IrStmt statement : block.statements()) {
			if (!(statement instanceof IrOp op) || op.canonical() != op || chain.contains(op)
					|| useGraph.useCount(op) != 1 || !guards.safeOperation(op)) return false;
			IrStmt consumer = useGraph.singleStatementConsumer(op);
			if (!(consumer instanceof IrOp next) || !guards.safeOperation(next)) return false;
		}
		return true;
	}

	private static boolean knownReference(@NotNull IrOp op) {
		return op.irType().kind() == me.darknet.dex.convert.ir.value.IrTypeKind.REFERENCE
				&& !op.isImprecise() && !op.isUnknown();
	}

	private static @org.jetbrains.annotations.Nullable IrBlock blockContaining(@NotNull IrMethod method,
	                                                                            @NotNull IrStmt statement) {
		for (IrBlock block : method.blocks()) {
			if (block.statements().contains(statement) || block.terminator() == statement) return block;
		}
		return null;
	}

	private static boolean isLoopBlock(@NotNull IrBlock block) {
		for (IrBlock successor : block.successors())
			if (canReach(successor, block)) return true;
		return false;
	}

	private static boolean canReach(@NotNull IrBlock from, @NotNull IrBlock target) {
		Set<IrBlock> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayList<IrBlock> work = new ArrayList<>();
		work.add(from);
		while (!work.isEmpty()) {
			IrBlock current = work.removeLast();
			if (current == target) return true;
			if (visited.add(current)) work.addAll(current.successors());
		}
		return false;
	}

	private static boolean transparentGlue(@NotNull List<IrBlock> glue) {
		return glue.stream().allMatch(block -> block.phis().isEmpty() && block.exceptionValue() == null
				&& block.statements().isEmpty() && block.exceptionalSuccessors().isEmpty()
				&& block.terminator() != null && block.terminator().kind() == IrTerminatorKind.GOTO);
	}

	private static int inputIndex(@NotNull IrStmt statement, @NotNull IrValue value) {
		List<IrValue> inputs = switch (statement) {
			case IrOp op -> op.inputs();
			case IrEffect effect -> effect.inputs();
			case IrTerminator terminator -> terminator.inputs();
		};
		for (int index = 0; index < inputs.size(); index++)
			if (inputs.get(index).canonical() == value.canonical()) return index;
		return -1;
	}

	private static @NotNull String receiverChainRejectionReason(@NotNull IrMethod method,
	                                                            @NotNull LoweringUseGraph useGraph,
	                                                            @NotNull JvmOptimizationGuards guards,
	                                                            @NotNull List<IrOp> operations,
	                                                            @NotNull IrStmt terminal,
	                                                            @NotNull IrBlock producerBlock,
	                                                            @NotNull IrBlock consumerBlock,
	                                                            @NotNull List<IrBlock> glue) {
		if (!guards.safeStatement(terminal)) return "terminal consumer semantics are incomplete";
		for (IrOp operation : operations)
			if (!guards.sameAggressiveBoundary(operation, terminal))
				return "receiver-chain crosses a nested resource or exception boundary";
		for (IrOp operation : operations) {
			if (!operation.semantics().complete())
				return "receiver-chain descriptor is incomplete for " + operation.kind();
			if (!isConstructor(operation) && !knownReference(operation))
				return "receiver-chain result is not a precise reference for " + operation.kind();
			if ((isConstructor(operation) ? !guards.safeStatement(operation) : !guards.safeOperation(operation)))
				return "receiver-chain operation has an unsafe input: " + operation.kind();
			for (IrValue input : operation.inputs()) {
				IrValue canonical = input.canonical();
				if (operations.stream().anyMatch(candidate -> candidate.canonical() == canonical)) continue;
				if (canonical instanceof IrUnknown || canonical.isImprecise())
					return "receiver-chain input is unknown or imprecise for " + operation.kind();
				if (canonical.stackOnly() && !canonical.hasLocal())
					return "receiver-chain input is stack-only without a materialized local for " + operation.kind();
				if (!(canonical instanceof IrConstant) && !canonical.hasLocal())
					return "receiver-chain input has no authoritative local for " + operation.kind();
			}
		}
		if (!receiverLayoutProof(method, useGraph, guards,
				operations, terminal, producerBlock, consumerBlock))
			return operations.stream().map(operation -> blockContaining(method, operation)).distinct().count() == 1
					? "receiver operations are not adjacent"
					: "receiver operations are not on a linear materializable path";
		if (!protectedProfile(method, producerBlock).equals(protectedProfile(method, consumerBlock)))
			return "receiver-chain protected coverage differs";
		return "receiver-chain exceptional or category proof failed";
	}

	private static void addDirectCandidate(@NotNull List<JvmSingleUseCandidate> candidates,
	                                       @NotNull IrMethod method,
	                                       @NotNull JvmOptimizationGuards guards,
	                                       @NotNull IrOp producer,
	                                       @NotNull IrBlock producerBlock,
	                                       @NotNull LoweringUseGraph.UseSite site) {
		IrStmt consumer = site.consumer();
		IrBlock consumerBlock = site.block();
		JvmSingleUseCandidate.Mode mode = consumer instanceof IrTerminator terminator
				&& terminator.kind() == IrTerminatorKind.RETURN
				? JvmSingleUseCandidate.Mode.DIRECT_RETURN : JvmSingleUseCandidate.Mode.DIRECT_ARGUMENT;
		List<IrBlock> glue = transparentPath(method, producerBlock, consumerBlock);
		boolean proof = basicProof(method, guards, producer, consumer, producerBlock, consumerBlock, glue)
				&& (inputOrderProof(producerBlock, consumerBlock, producer, consumer, mode)
				|| guards.allowDeferredReceiverFieldRead(producer, consumer, glue));
		String reason = proof ? "" : rejectionReason(method, guards, producer, consumer,
				producerBlock, consumerBlock, glue);
		candidates.add(candidate(method, producer, List.of(producer), consumer, producerBlock, consumerBlock,
				glue, site.inputIndex(), mode, proof, reason));
	}

	private static void addConstructorCandidates(@NotNull List<JvmSingleUseCandidate> candidates,
	                                             @NotNull IrMethod method,
	                                             @NotNull LoweringUseGraph useGraph,
	                                             @NotNull JvmOptimizationGuards guards,
	                                             @NotNull IrOp allocation,
	                                             @NotNull IrBlock block) {
		List<LoweringUseGraph.UseSite> sites = useGraph.useSites(allocation);
		IrOp constructor = sites.stream().filter(site -> !site.phi() && site.consumer() instanceof IrOp)
				.map(site -> (IrOp) site.consumer()).filter(JvmSingleUsePlanner::isConstructor).findFirst().orElse(null);
		if (constructor == null || constructor.inputs().isEmpty()
				|| constructor.inputs().getFirst().canonical() != allocation) return;
		int constructorIndex = block.statements().indexOf(constructor);
		int allocationIndex = block.statements().indexOf(allocation);
		if (allocationIndex < 0 || constructorIndex != allocationIndex + 1) return;

		if (sites.size() == 1) {
			boolean proof = basicDeadConstructionProof(method, guards, allocation, constructor, block);
			candidates.add(candidate(method, allocation, List.of(allocation, constructor), constructor, block, block,
					List.of(), 0, JvmSingleUseCandidate.Mode.DEAD_CONSTRUCTION, proof,
					proof ? "" : "dead construction crosses an observable boundary"));
			return;
		}
		if (sites.size() != 2) return;
		LoweringUseGraph.UseSite other = sites.stream()
				.filter(site -> site.consumer() != constructor && !site.phi()).findFirst().orElse(null);
		if (other == null || other.consumer() == null || other.block() != block) return;
		IrStmt consumer = other.consumer();
		int consumerIndex = block.statements().indexOf(consumer);
		if (consumerIndex != constructorIndex + 1 && consumer != block.terminator()) return;
		JvmSingleUseCandidate.Mode mode = consumer instanceof IrTerminator terminator
				&& terminator.kind() == IrTerminatorKind.THROW
				? JvmSingleUseCandidate.Mode.CONSTRUCTOR_TO_THROW
				: JvmSingleUseCandidate.Mode.CONSTRUCTOR_CHAIN;
		boolean proof = basicProof(method, guards, allocation, consumer, block, block, List.of())
				&& guards.safeStatement(constructor) && constructor.inputs().stream().skip(1).allMatch(guards::safeValue);
		candidates.add(candidate(method, allocation, List.of(allocation, constructor), consumer, block, block,
				List.of(), other.inputIndex(), mode, proof,
				proof ? "" : "constructor chain has an unsafe input or boundary"));
	}

	private static boolean basicDeadConstructionProof(@NotNull IrMethod method,
	                                                  @NotNull JvmOptimizationGuards guards,
	                                                  @NotNull IrOp allocation,
	                                                  @NotNull IrOp constructor,
	                                                  @NotNull IrBlock block) {
		return guards.safeOperation(allocation) && guards.safeStatement(constructor)
				&& constructor.type().descriptor().equals("V")
				&& block.exceptionValue() == null && block.exceptionalSuccessors().isEmpty()
				&& protectedProfile(method, block).isEmpty()
				&& constructor.inputs().stream().skip(1).allMatch(guards::safeValue);
	}

	private static boolean basicProof(@NotNull IrMethod method, @NotNull JvmOptimizationGuards guards,
	                                 @NotNull IrOp producer, @NotNull IrStmt consumer,
	                                 @NotNull IrBlock producerBlock, @NotNull IrBlock consumerBlock,
	                                 @NotNull List<IrBlock> glue) {
		if (!guards.safeOperation(producer) || !guards.safeStatement(consumer)
				|| producer.isImprecise() || producer.isUnknown() || !producer.hasLocal()) return false;
		if (producerBlock.exceptionValue() != null || consumerBlock.exceptionValue() != null
				|| !producerBlock.phis().isEmpty() || !consumerBlock.phis().isEmpty()) return false;
		boolean deferredFieldRead = guards.allowDeferredReceiverFieldRead(producer, consumer, glue);
		if (!deferredFieldRead && !guards.sameAggressiveBoundary(producer, consumer)) return false;
		if (!protectedProfile(method, producerBlock).equals(protectedProfile(method, consumerBlock))) return false;
		if (!deferredFieldRead && !exceptionProfile(producerBlock).equals(exceptionProfile(consumerBlock))) return false;
		if (producerBlock == consumerBlock) return true;
		if (deferredFieldRead) return true;
		return glue.stream().allMatch(JvmSingleUsePlanner::transparentGlue)
				&& producerBlock.terminator() != null && producerBlock.terminator().kind() == IrTerminatorKind.GOTO;
	}

	private static boolean inputOrderProof(@NotNull IrBlock producerBlock, @NotNull IrBlock consumerBlock,
	                                      @NotNull IrOp producer, @NotNull IrStmt consumer,
	                                      @NotNull JvmSingleUseCandidate.Mode mode) {
		int producerIndex = producerBlock.statements().indexOf(producer);
		if (producerBlock == consumerBlock) {
			if (mode == JvmSingleUseCandidate.Mode.DIRECT_RETURN && consumer == producerBlock.terminator())
				return producerIndex == producerBlock.statements().size() - 1;
			int consumerIndex = producerBlock.statements().indexOf(consumer);
			return producerIndex >= 0 && consumerIndex == producerIndex + 1;
		}
		if (producerIndex != producerBlock.statements().size() - 1) return false;
		if (mode == JvmSingleUseCandidate.Mode.DIRECT_RETURN)
			return consumer == consumerBlock.terminator() && consumerBlock.statements().isEmpty();
		return !consumerBlock.statements().isEmpty() && consumerBlock.statements().getFirst() == consumer;
	}

	private static @NotNull List<IrBlock> transparentPath(@NotNull IrMethod method,
	                                                      @NotNull IrBlock source,
	                                                      @NotNull IrBlock target) {
		if (source == target) return List.of();
		List<IrBlock> path = new ArrayList<>();
		Set<IrBlock> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		IrBlock cursor = source;
		while (cursor != target && visited.add(cursor)) {
			if (cursor.successors().size() != 1) return List.of();
			IrBlock next = cursor.successors().getFirst();
			if (next != target) path.add(next);
			cursor = next;
		}
		return cursor == target ? path : List.of();
	}

	private static boolean transparentGlue(@NotNull IrBlock block) {
		return block.phis().isEmpty() && block.exceptionValue() == null && block.statements().isEmpty()
				&& block.exceptionalSuccessors().isEmpty() && block.terminator() != null
				&& block.terminator().kind() == IrTerminatorKind.GOTO;
	}

	private static @NotNull String rejectionReason(@NotNull IrMethod method, @NotNull JvmOptimizationGuards guards,
	                                               @NotNull IrOp producer, @NotNull IrStmt consumer,
	                                               @NotNull IrBlock producerBlock, @NotNull IrBlock consumerBlock,
	                                               @NotNull List<IrBlock> glue) {
		if (!guards.safeOperation(producer) || !guards.safeStatement(consumer)) return "semantic contract or value is incomplete";
		if (!guards.sameAggressiveBoundary(producer, consumer))
			return "producer and consumer cross a nested resource or exception boundary";
		if (producerBlock == consumerBlock) return "producer and consumer are not adjacent in source order";
		if (glue.isEmpty()) return "consumer crosses a non-transparent CFG path";
		if (!protectedProfile(method, producerBlock).equals(protectedProfile(method, consumerBlock)))
			return "protected-range coverage differs";
		return "exceptional transfer or handler state differs";
	}

	private static @NotNull JvmSingleUseCandidate candidate(@NotNull IrMethod method, @NotNull IrOp producer,
	                                                        @NotNull List<IrOp> operations, @NotNull IrStmt consumer,
	                                                        @NotNull IrBlock producerBlock, @NotNull IrBlock consumerBlock,
	                                                        @NotNull List<IrBlock> glue, int inputIndex,
	                                                        @NotNull JvmSingleUseCandidate.Mode mode,
	                                                        boolean proof, @NotNull String reason) {
		return new JvmSingleUseCandidate(producer, operations, consumer, producerBlock, consumerBlock, glue,
				inputIndex, mode, 1, true, producer.semantics(), semantics(consumer), producer.irType(),
				List.copyOf(producerBlock.exceptionEdges()), exceptionProfile(producerBlock), sourceOffset(method, producer),
				proof, reason);
	}

	private static me.darknet.dex.convert.ir.analysis.IrInstructionSemantics semantics(@NotNull IrStmt statement) {
		return switch (statement) {
			case IrOp op -> op.semantics();
			case IrEffect effect -> effect.semantics();
			case IrTerminator terminator -> terminator.semantics();
		};
	}

	private static int sourceOffset(@NotNull IrMethod method, @NotNull IrOp op) {
		if (method.source().getCode() != null
				&& op.payload() instanceof me.darknet.dex.tree.definitions.instructions.Instruction instruction) {
			Integer offset = method.source().getCode().offsetOf(instruction);
			if (offset != null) return offset;
		}
		return -1;
	}

	private static boolean isConstructor(@NotNull IrOp op) {
		return op.payload() instanceof InvokeInstruction instruction
				&& instruction.opcode() == Invoke.DIRECT && "<init>".equals(instruction.name());
	}

	private static boolean isReceiverReturningInvoke(@NotNull IrOp op) {
		if (!(op.payload() instanceof InvokeInstruction instruction)
				|| instruction.opcode() == Invoke.STATIC
				|| instruction.type().returnType().descriptor().equals("V")) return false;
		return instruction.type().returnType().descriptor().equals(instruction.owner().descriptor());
	}

	private static @NotNull List<String> protectedProfile(@NotNull IrMethod method, @NotNull IrBlock block) {
		List<String> profile = new ArrayList<>();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			if (!region.protectedBlocks().contains(block)) continue;
			for (IrExceptionHandler handler : region.handlers()) {
				String type = handler.handler() == null || handler.handler().isCatchAll() ? "*"
						: handler.handler().exceptionType().descriptor();
				profile.add(region.startOffset() + ":" + region.endOffset() + ":" + type);
			}
		}
		return profile;
	}

	private static @NotNull List<String> exceptionProfile(@NotNull IrBlock block) {
		return block.exceptionEdges().stream()
				.map(edge -> edge.handlerBlock().index() + ":" + edge.throwMask())
				.sorted().toList();
	}

	private static @NotNull List<String> protectedHandlerProfile(@NotNull IrMethod method,
	                                                              @NotNull IrBlock block) {
		List<String> profile = new ArrayList<>();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			if (!region.protectedBlocks().contains(block)) continue;
			for (IrExceptionHandler handler : region.handlers()) {
				String type = handler.handler() == null || handler.handler().isCatchAll() ? "*"
						: handler.handler().exceptionType().descriptor();
				int target = handler.handlerBlock() == null ? -1 : handler.handlerBlock().index();
				profile.add(region.startOffset() + ":" + region.endOffset() + ":" + target + ":" + type);
			}
		}
		return profile.stream().sorted().toList();
	}
}
