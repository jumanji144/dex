package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionRegion;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrParameter;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Proof predicates for optional JVM expression lowering.  A failed predicate
 * means that the caller must use ordinary local materialization.
 */
final class JvmOptimizationGuards {
	private final IrMethod method;
	private final LoweringUseGraph useGraph;
	private final JvmLoweringFacts facts;

	JvmOptimizationGuards(@NotNull IrMethod method, @NotNull LoweringUseGraph useGraph) {
		this(method, useGraph, JvmLoweringFacts.analyze(method, useGraph));
	}

	JvmOptimizationGuards(@NotNull IrMethod method, @NotNull LoweringUseGraph useGraph,
	                      @NotNull JvmLoweringFacts facts) {
		this.method = method;
		this.useGraph = useGraph;
		this.facts = facts;
	}

	boolean safeOperation(@NotNull IrOp op) {
		if (op.canonical() != op || !op.semantics().complete() || !safeValue(op)) return false;
		for (IrValue input : op.inputs())
			if (!safeValue(input)) return false;
		return true;
	}

	boolean safeValue(@NotNull IrValue value) {
		IrValue canonical = value.canonical();
		JvmValueFacts valueFacts = facts.findValue(canonical);
		if (valueFacts != null) return valueFacts.known();
		if (canonical instanceof IrUnknown || canonical.isImprecise()) return false;
		IrTypeKind kind = canonical.irType().kind();
		return kind != IrTypeKind.UNKNOWN && kind != IrTypeKind.TOP && kind != IrTypeKind.BOTTOM;
	}

	boolean safeStatement(@NotNull IrStmt statement) {
		if (statement instanceof IrOp op)
			return op.semantics().complete()
					&& (ConversionSupport.isVoidType(op.type()) || safeValue(op))
					&& op.inputs().stream().allMatch(this::safeValue);
		if (statement instanceof IrEffect effect)
			return effect.semantics().complete() && effect.inputs().stream().allMatch(this::safeValue);
		if (statement instanceof IrTerminator terminator)
			return terminator.semantics().complete() && terminator.inputs().stream().allMatch(this::safeValue);
		return false;
	}

	boolean sameAggressiveBoundary(@NotNull IrStmt producer, @NotNull IrStmt consumer) {
		return facts.sameAggressiveBoundary(producer, consumer);
	}

	boolean sameBlock(@NotNull IrStmt first, @NotNull IrStmt second) {
		IrBlock firstBlock = blockContaining(first);
		return firstBlock != null && firstBlock == blockContaining(second);
	}

	boolean allowInline(@NotNull IrOp op, @NotNull IrStmt consumer) {
		if (!safeOperation(op) || !safeStatement(consumer) || useGraph.useCount(op) != 1)
			return false;
		if (sameBlock(op, consumer))
			return !crossesExceptionalBoundary(op, consumer);
		return allowAdjacentInline(op, consumer);
	}

	/**
	 * Allows an aggressive lowering to re-read a field from the non-null method
	 * receiver at its sole consumer. DEX commonly isolates each GETFIELD in a
	 * block even though the source operation is simply {@code this.field} at the
	 * following call. The proof permits only a linear path and only a GETFIELD
	 * whose receiver is the JVM {@code this}; it never moves an arbitrary field
	 * read or a field write across control flow.
	 */
	boolean allowDeferredReceiverFieldRead(@NotNull IrOp producer, @NotNull IrStmt consumer,
	                                       @NotNull List<IrBlock> glue) {
		if (!(producer.payload() instanceof InstanceFieldInstruction field)
				|| field.opcode() >= me.darknet.dex.file.instructions.Opcodes.IPUT
				|| useGraph.useCount(producer) != 1
				|| !safeOperation(producer) || !safeStatement(consumer)
				|| producer.inputs().size() != 1 || !isThis(producer.inputs().getFirst())) return false;
		IrBlock source = blockContaining(producer);
		IrBlock target = blockContaining(consumer);
		if (source == null || target == null || source == target
				|| source.exceptionValue() != null || target.exceptionValue() != null
				|| !source.phis().isEmpty() || !target.phis().isEmpty()
				|| source.statements().indexOf(producer) != source.statements().size() - 1
				|| target.statements().indexOf(consumer) != 0
				|| source.terminator() == null
				|| source.terminator().kind() != me.darknet.dex.convert.ir.statement.IrTerminatorKind.GOTO)
			return false;
		IrBlock cursor = source;
		Set<IrBlock> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		while (cursor != target) {
			if (!visited.add(cursor) || cursor.successors().size() != 1) return false;
			IrBlock next = cursor.successors().getFirst();
			if (next != target && (next.exceptionValue() != null || !next.phis().isEmpty()
					|| next.exceptionalSuccessors().isEmpty() && next.statements().isEmpty()
					&& (next.terminator() == null
					|| next.terminator().kind() != me.darknet.dex.convert.ir.statement.IrTerminatorKind.GOTO)))
				return false;
			cursor = next;
		}
		return true;
	}

	private boolean isThis(@NotNull IrValue value) {
		if ((method.source().getAccess() & org.objectweb.asm.Opcodes.ACC_STATIC) != 0
				|| method.source().getCode() == null || !(value.canonical() instanceof IrParameter parameter)) return false;
		int receiverRegister = method.source().getCode().getRegisters() - method.source().getCode().getIn();
		return parameter.register() == receiverRegister;
	}

	/**
	 * Proves that a value-producing operation can be re-emitted at its sole
	 * consumer in the immediately following CFG block (possibly through
	 * transparent linear glue).  DEX splits protected
	 * code after individual throwing instructions, which otherwise forces a
	 * harmless producer/consumer pair into two JVM locals.  The proof is
	 * intentionally stricter than ordinary same-block fusion: there may be no
	 * phi or handler state, the producer must be the final statement before an
	 * unconditional edge, and both blocks must have identical exceptional
	 * transfer metadata.
	 */
	boolean allowAdjacentInline(@NotNull IrOp op, @NotNull IrStmt consumer) {
		if (sameBlock(op, consumer) || op.payload() instanceof NewInstanceInstruction)
			return false;
		if (op.payload() instanceof InvokeInstruction instruction
				&& instruction.opcode() == Invoke.DIRECT && "<init>".equals(instruction.name()))
			return false;
		IrBlock source = blockContaining(op);
		IrBlock target = blockContaining(consumer);
		if (source == null || target == null || source == target
				|| source.exceptionValue() != null || target.exceptionValue() != null
				|| !source.phis().isEmpty() || !target.phis().isEmpty()
				|| source.terminator() == null || source.terminator().kind()
						!= me.darknet.dex.convert.ir.statement.IrTerminatorKind.GOTO
				|| source.statements().indexOf(op) != source.statements().size() - 1
				|| target.statements().indexOf(consumer) != 0
				|| !sameExceptionalTransferForInline(source, target)) return false;
		IrBlock cursor = source;
		Set<IrBlock> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		while (cursor != target) {
			if (!visited.add(cursor) || cursor.successors().size() != 1) return false;
			IrBlock next = cursor.successors().getFirst();
			if (next != target && (next.exceptionValue() != null || !next.phis().isEmpty()
					|| !next.statements().isEmpty() || next.terminator() == null
					|| next.terminator().kind() != me.darknet.dex.convert.ir.statement.IrTerminatorKind.GOTO
					|| !next.exceptionalSuccessors().isEmpty())) return false;
			cursor = next;
		}
		return source.exceptionalSuccessors().equals(target.exceptionalSuccessors());
	}

	/**
	 * Proves that a fluent receiver-returning invoke can stay on the operand
	 * stack until the immediately consuming invoke. This is deliberately
	 * narrower than general expression fusion: the producer must be the
	 * consumer's receiver, both operations must be in one ordinary block, and
	 * the block must not have an exceptional transfer.
	 */
	boolean allowReceiverReturningChain(@NotNull IrOp producer, @NotNull IrStmt consumer) {
		if (!(producer.payload() instanceof InvokeInstruction producerInstruction)
				|| producerInstruction.opcode() == Invoke.STATIC
				|| !producerInstruction.type().returnType().descriptor().equals(producerInstruction.owner().descriptor()))
			return false;
		if (!(consumer instanceof IrOp consumerOp)
				|| !(consumerOp.payload() instanceof InvokeInstruction)
				|| consumerOp.inputs().isEmpty()
				|| consumerOp.inputs().getFirst().canonical() != producer)
			return false;
		IrBlock block = blockContaining(producer);
		return block != null && block == blockContaining(consumer)
				&& block.exceptionValue() == null && block.exceptionalSuccessors().isEmpty()
				&& safeOperation(producer) && safeStatement(consumer)
				&& useGraph.useCount(producer) == 1;
	}

	boolean allowCarry(@NotNull IrBlock block, @NotNull IrOp producer, @NotNull IrStmt consumer) {
		if (!safeOperation(producer) || !safeStatement(consumer) || blockContaining(consumer) != block
				|| useGraph.useCount(producer) != 1 || block.exceptionValue() != null) return false;
		int producerIndex = block.statements().indexOf(producer);
		int consumerIndex = block.statements().indexOf(consumer);
		if (producerIndex < 0) return false;
		if (consumer instanceof IrOp consumerOp) {
			return consumerIndex == producerIndex + 1
					&& !consumerOp.inputs().isEmpty()
					&& consumerOp.inputs().getFirst().canonical() == producer;
		}
		// A branch terminator is the only non-operation consumer allowed to
		// consume a carried value.  It is emitted immediately after the block's
		// statements, so the stack value cannot cross a label or another effect.
		if (!(consumer instanceof IrTerminator terminator)
				|| (terminator.kind() != me.darknet.dex.convert.ir.statement.IrTerminatorKind.IF
				&& terminator.kind() != me.darknet.dex.convert.ir.statement.IrTerminatorKind.IF_ZERO))
			return false;
		return consumerIndex == -1 && producerIndex == block.statements().size() - 1
				&& terminator.inputs().stream().anyMatch(input -> input.canonical() == producer);
	}

	boolean allowConstructorChain(@NotNull IrOp constructor, @NotNull IrStmt consumer) {
		boolean voidConstructor = ConversionSupport.isVoidType(constructor.type());
		if (voidConstructor) {
			// A DEX <init> is represented as a void invoke whose observable
			// consumer is the constructed receiver, not the invoke result. Do not
			// require a value use-count of one for this effectful statement.
			if (!safeStatement(constructor) || !safeStatement(consumer) || !sameBlock(constructor, consumer)
					|| crossesExceptionalBoundary(constructor, consumer)) return false;
		} else if (!allowInline(constructor, consumer)) {
			return false;
		}
		IrBlock block = blockContaining(constructor);
		if (block == null || !block.exceptionalSuccessors().isEmpty()) return false;
		return constructor.inputs().stream().skip(1).allMatch(this::safeValue);
	}

	boolean allowConstructedReceiver(@NotNull IrOp receiver, @NotNull IrStmt consumer) {
		if (!safeOperation(receiver) || !safeStatement(consumer) || !sameBlock(receiver, consumer)) return false;
		if (useGraph.useCount(receiver) == 2) return true;
		return allowLinearConstructedReceiverChain(receiver, consumer);
	}

	private boolean allowLinearConstructedReceiverChain(@NotNull IrOp receiver, @NotNull IrStmt consumer) {
		if (!(receiver.payload() instanceof me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction)) return false;
		IrBlock block = blockContaining(receiver);
		if (block == null || block.exceptionValue() != null || !block.exceptionalSuccessors().isEmpty()) return false;
		List<IrStmt> uses = new ArrayList<>();
		for (IrStmt statement : block.statements())
			if (usesValue(statement, receiver)) uses.add(statement);
		if (block.terminator() != null && usesValue(block.terminator(), receiver)) uses.add(block.terminator());
		if (uses.size() != useGraph.useCount(receiver) || uses.size() < 3 || !uses.contains(consumer)) return false;
		int constructorIndex = -1;
		for (int index = 0; index < uses.size(); index++) {
			IrStmt statement = uses.get(index);
			if (statement instanceof IrOp op && op.payload() instanceof InvokeInstruction instruction
					&& instruction.opcode() == Invoke.DIRECT && "<init>".equals(instruction.name())) {
				if (constructorIndex >= 0 || !op.inputs().stream().findFirst().map(value -> value.canonical() == receiver).orElse(false))
					return false;
				if (!safeStatement(op)) return false;
				constructorIndex = index;
			}
		}
		if (constructorIndex < 0) return false;
		if (uses.indexOf(consumer) < constructorIndex) return false;
		for (int index = constructorIndex + 1; index < uses.size(); index++) {
			IrStmt statement = uses.get(index);
			if (!(statement instanceof IrOp op) || !(op.payload() instanceof InvokeInstruction instruction)
					|| op.inputs().isEmpty() || op.inputs().getFirst().canonical() != receiver
					|| !safeOperation(op)) return false;
			if (!isReceiverReturningInvoke(instruction) && index != uses.size() - 1) return false;
		}
		return true;
	}

	private boolean usesValue(@NotNull IrStmt statement, @NotNull IrValue value) {
		List<IrValue> inputs = switch (statement) {
			case IrOp op -> op.inputs();
			case IrEffect effect -> effect.inputs();
			case IrTerminator terminator -> terminator.inputs();
		};
		return inputs.stream().anyMatch(input -> input.canonical() == value.canonical());
	}

	private static boolean isReceiverReturningInvoke(@NotNull InvokeInstruction instruction) {
		return instruction.opcode() != Invoke.STATIC
				&& instruction.type().returnType().descriptor().equals(instruction.owner().descriptor());
	}

	boolean allowArrayChain(@NotNull IrOp array, @NotNull List<IrEffect> stores, @NotNull IrEffect put) {
		if (!safeOperation(array) || !safeStatement(put) || useGraph.useCount(array) != stores.size() + 1)
			return false;
		IrBlock block = blockContaining(array);
		if (block == null || block.exceptionValue() != null || !block.exceptionalSuccessors().isEmpty()) return false;
		return stores.stream().allMatch(effect -> safeStatement(effect) && blockContaining(effect) == block);
	}

	/**
	 * Proves that a DEX synthetic lambda can be reconstructed as an invokedynamic
	 * lambda at its sole consumer. The proof remains deliberately local: the
	 * lambda object and constructor must stay in one block; captured lambdas
	 * additionally require an adjacent constructor/consumer pair, and no
	 * exceptional successor may be bypassed.
	 */
	boolean allowSyntheticLambda(@NotNull IrOp comparator, @NotNull IrOp constructor,
	                            @NotNull IrOp consumer) {
		if (!(comparator.payload() instanceof NewInstanceInstruction)
				|| !(constructor.payload() instanceof InvokeInstruction constructorInstruction)
				|| constructorInstruction.opcode() != Invoke.DIRECT
				|| !"<init>".equals(constructorInstruction.name())
				|| constructor.inputs().isEmpty()
				|| constructor.inputs().getFirst().canonical() != comparator
				|| comparator.inputs().size() != 0
				|| useGraph.useCount(comparator) != 2) return false;
		IrBlock block = blockContaining(comparator);
		IrBlock constructorBlock = blockContaining(constructor);
		IrBlock consumerBlock = blockContaining(consumer);
		boolean sameBlock = block != null && block == constructorBlock && constructorBlock == consumerBlock;
		boolean adjacentBlocks = allowAdjacentSyntheticLambdaBlocks(block, constructorBlock, consumerBlock,
				constructor, consumer);
		if (!sameBlock && !adjacentBlocks) return false;
		if (block == null || block.exceptionValue() != null || !block.exceptionalSuccessors().isEmpty()
				|| !constructor.inputs().stream().allMatch(this::safeValue)) return false;
		int constructorIndex = block.statements().indexOf(constructor);
		if (sameBlock) {
			int consumerIndex = block.statements().indexOf(consumer);
			if (constructor.inputs().size() > 1 && consumerIndex != constructorIndex + 1) return false;
		}
		return safeStatement(comparator) && safeStatement(constructor) && safeStatement(consumer);
	}

	private boolean allowAdjacentSyntheticLambdaBlocks(@Nullable IrBlock comparatorBlock,
	                                                   @Nullable IrBlock constructorBlock,
	                                                   @Nullable IrBlock consumerBlock,
	                                                   @NotNull IrOp constructor,
	                                                   @NotNull IrOp consumer) {
		if (comparatorBlock == null || constructorBlock == null || consumerBlock == null
				|| comparatorBlock == constructorBlock || constructorBlock == consumerBlock
				|| comparatorBlock.successors().size() != 1
				|| comparatorBlock.successors().getFirst() != constructorBlock
				|| constructorBlock.successors().size() != 1
				|| constructorBlock.successors().getFirst() != consumerBlock
				|| !comparatorBlock.phis().isEmpty() || !constructorBlock.phis().isEmpty()
				|| !consumerBlock.phis().isEmpty() || !comparatorBlock.exceptionalSuccessors().isEmpty()
				|| constructorBlock.exceptionValue() != null || consumerBlock.exceptionValue() != null
				|| constructorBlock.statements().size() != 1
				|| constructorBlock.statements().getFirst() != constructor
				|| consumerBlock.statements().isEmpty()
				|| consumerBlock.statements().getFirst() != consumer)
			return false;
		return sameExceptionalTransfer(constructorBlock, consumerBlock);
	}

	private boolean sameExceptionalTransfer(@NotNull IrBlock first, @NotNull IrBlock second) {
		if (first.exceptionalSuccessors().size() != second.exceptionalSuccessors().size()
				|| first.exceptionEdges().size() != second.exceptionEdges().size()) return false;
		for (var firstEdge : first.exceptionEdges()) {
			var secondEdge = second.exceptionEdges().stream()
					.filter(candidate -> candidate.handlerBlock() == firstEdge.handlerBlock()
							&& Objects.equals(candidate.handler(), firstEdge.handler())
							&& candidate.throwMask() == firstEdge.throwMask())
					.findFirst().orElse(null);
			if (secondEdge == null || !sameState(first.exceptionalExitStates().get(firstEdge),
					second.exceptionalExitStates().get(secondEdge))) return false;
		}
		return true;
	}

	private boolean sameExceptionalTransferForInline(@NotNull IrBlock first, @NotNull IrBlock second) {
		if (first.exceptionalSuccessors().size() != second.exceptionalSuccessors().size()
				|| first.exceptionEdges().size() != second.exceptionEdges().size()) return false;
		for (var firstEdge : first.exceptionEdges()) {
			var secondEdge = second.exceptionEdges().stream()
					.filter(candidate -> candidate.handlerBlock() == firstEdge.handlerBlock()
							&& Objects.equals(candidate.handler(), firstEdge.handler())
							&& candidate.throwMask() == firstEdge.throwMask())
					.findFirst().orElse(null);
			if (secondEdge == null || !sameStateExceptDeadHandlerValues(
					first.exceptionalExitStates().get(firstEdge),
					second.exceptionalExitStates().get(secondEdge), firstEdge.handlerBlock()))
				return false;
		}
		return true;
	}

	private boolean sameStateExceptDeadHandlerValues(@Nullable IrValue[] first, @Nullable IrValue[] second,
	                                                 @NotNull IrBlock handler) {
		if (first == null || second == null) return first == second;
		if (first.length != second.length) return false;
		Set<IrValue> handlerUses = handlerReachableUses(handler);
		for (int index = 0; index < first.length; index++) {
			IrValue left = first[index] == null ? null : first[index].canonical();
			IrValue right = second[index] == null ? null : second[index].canonical();
			if (left == right) continue;
			if ((left != null && handlerUses.contains(left)) || (right != null && handlerUses.contains(right)))
				return false;
		}
		return true;
	}

	private Set<IrValue> handlerReachableUses(@NotNull IrBlock handler) {
		Set<IrBlock> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<IrValue> values = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<IrBlock> work = new ArrayDeque<>();
		work.add(handler);
		while (!work.isEmpty()) {
			IrBlock block = work.removeFirst();
			if (!visited.add(block)) continue;
			for (IrPhi phi : block.phis())
				for (IrValue value : phi.operands().values()) values.add(value.canonical());
			for (IrStmt statement : block.statements()) addInputs(statement, values);
			if (block.terminator() != null) addInputs(block.terminator(), values);
			work.addAll(block.successors());
		}
		return values;
	}

	private void addInputs(@NotNull IrStmt statement, @NotNull Set<IrValue> values) {
		switch (statement) {
			case IrOp op -> op.inputs().forEach(value -> values.add(value.canonical()));
			case IrEffect effect -> effect.inputs().forEach(value -> values.add(value.canonical()));
			case IrTerminator terminator -> terminator.inputs().forEach(value -> values.add(value.canonical()));
		}
	}

	private boolean sameState(@Nullable IrValue[] first, @Nullable IrValue[] second) {
		if (first == null || second == null) return first == second;
		if (first.length != second.length) return false;
		for (int index = 0; index < first.length; index++) {
			IrValue left = first[index] == null ? null : first[index].canonical();
			IrValue right = second[index] == null ? null : second[index].canonical();
			if (left != right) return false;
		}
		return true;
	}

	boolean allowDirectReturn(@NotNull IrBlock source, @NotNull IrBlock target, @NotNull IrValue value) {
		return safeValue(value) && (value.isZeroConstant() && ConversionSupport.isReferenceType(method.source().getType().returnType())
				|| compatibleWithMethodReturn(value.type()))
				&& source.exceptionValue() == null && source.exceptionalSuccessors().isEmpty()
				&& target.exceptionValue() == null && target.exceptionalSuccessors().isEmpty();
	}

	private boolean compatibleWithMethodReturn(@NotNull me.darknet.dex.tree.type.ClassType type) {
		me.darknet.dex.tree.type.ClassType expected = method.source().getType().returnType();
		if (ConversionSupport.isReferenceType(expected)) return ConversionSupport.isReferenceType(type);
		if (ConversionSupport.isLongType(expected)) return ConversionSupport.isLongType(type);
		if (ConversionSupport.isDoubleType(expected)) return ConversionSupport.isDoubleType(type);
		if (ConversionSupport.isFloatType(expected)) return ConversionSupport.isFloatType(type);
		return !ConversionSupport.isReferenceType(type) && !ConversionSupport.isWideType(type)
				&& !ConversionSupport.isFloatType(type);
	}

	/**
	 * Proves that a phi is only a control-flow spelling of one value.  This is
	 * deliberately stricter than a constant equality check: loop-carried phis,
	 * handler state and imprecise values must retain their materialized local.
	 */
	boolean allowPhiElision(@NotNull IrPhi phi) {
		if (phi.canonical() != phi || phi.operands().isEmpty() || !safeValue(phi)) return false;
		IrValue uniform = null;
		for (IrValue operand : phi.operands().values()) {
			IrValue canonical = operand.canonical();
			if (canonical == phi || !safeValue(canonical)) return false;
			if (uniform == null) {
				uniform = canonical;
			} else if (uniform != canonical && !sameConstant(uniform, canonical)) {
				return false;
			}
		}
		return uniform != null;
	}

	private boolean sameConstant(@NotNull IrValue first, @NotNull IrValue second) {
		Object left = first.constantValue();
		Object right = second.constantValue();
		if (left == null || right == null) return first.isZeroConstant() && second.isZeroConstant();
		return left.equals(right);
	}

	boolean allowResourceRegion(@NotNull IrExceptionRegion region) {
		if (region.protectedBlocks().isEmpty()) return false;
		for (IrBlock block : region.protectedBlocks()) {
			if (block.exceptionValue() != null) return false;
			// This refinement changes only label/range layout. It does not move
			// values or fuse statements, so input precision is not required here;
			// semantic completeness and ordinary (non-handler) block boundaries are.
			for (IrStmt statement : block.statements()) {
				if (statement instanceof IrOp op && !op.semantics().complete()) return false;
				if (statement instanceof IrEffect effect && !effect.semantics().complete()) return false;
				if (statement instanceof IrTerminator terminator && !terminator.semantics().complete()) return false;
			}
		}
		return true;
	}

	/**
	 * Resource planning has a narrower proof obligation than arbitrary handler
	 * coalescing.  A protected body may contain unrelated imprecise SSA state as
	 * long as the values that cross the cleanup boundary are authoritative.  The
	 * old region-wide predicate rejected those useful candidates unnecessarily.
	 */
	boolean allowAggressiveResourceRegion(@NotNull IrExceptionRegion region,
	                                      @NotNull JvmCleanupRegionPlan plan) {
		if (region.protectedBlocks().isEmpty() || region.handlers().isEmpty()
				|| !completeCleanupResource(plan)) return false;
		for (IrBlock block : region.protectedBlocks())
			if (!completeStatementSemantics(block)) return false;
		return true;
	}

	private boolean completeCleanupResource(@NotNull JvmCleanupRegionPlan plan) {
		if (!plan.hasMaterializedResource()) return false;
		if (plan.acquisition() != null && !plan.acquisition().inputs().stream().allMatch(this::materialized)) return false;
		if (plan.normalClose() == null || !plan.normalClose().inputs().stream().allMatch(this::materialized)) return false;
		if (plan.primaryException() != null && !materialized(plan.primaryException())) return false;
		if (plan.suppressedException() != null
				&& !plan.suppressedException().inputs().stream().allMatch(this::materialized)) return false;
		if (plan.rethrow() != null && !plan.rethrow().inputs().stream().allMatch(this::materialized)) return false;
		if (plan.nullResourceBlock() != null && plan.nullResourceBlock().terminator() != null
				&& !plan.nullResourceBlock().terminator().inputs().stream().allMatch(this::materialized)) return false;
		return true;
	}

	private boolean completeStatementSemantics(@NotNull IrBlock block) {
		for (IrStmt statement : block.statements())
			if (!hasCompleteSemantics(statement)) return false;
		return block.terminator() == null || hasCompleteSemantics(block.terminator());
	}

	private boolean hasCompleteSemantics(@NotNull IrStmt statement) {
		return switch (statement) {
			case IrOp op -> op.semantics().complete();
			case IrEffect effect -> effect.semantics().complete();
			case IrTerminator terminator -> terminator.semantics().complete();
		};
	}

	private boolean materialized(@NotNull IrValue value) {
		IrValue canonical = value.canonical();
		return !(canonical instanceof IrUnknown) && !canonical.isImprecise()
				&& !canonical.stackOnly() && (canonical.constantValue() != null || canonical.hasLocal());
	}

	private boolean crossesExceptionalBoundary(@NotNull IrStmt first, @NotNull IrStmt second) {
		IrBlock block = blockContaining(first);
		return block == null || block.exceptionalSuccessors().stream()
				.anyMatch(handler -> handler == blockContaining(second));
	}

	private IrBlock blockContaining(@NotNull IrStmt statement) {
		for (IrBlock block : method.blocks())
			if (block.statements().contains(statement) || block.terminator() == statement) return block;
		return null;
	}
}
