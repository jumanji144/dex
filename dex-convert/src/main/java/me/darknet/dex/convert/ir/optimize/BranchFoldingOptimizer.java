package me.darknet.dex.convert.ir.optimize;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optimizer that folds opaque branches into direct jumps and prunes unreachable code.
 */
public class BranchFoldingOptimizer implements IrOptimizer{
	@Override
	public void optimizeMethod(@NotNull IrOptimizationContext context, @NotNull IrMethod method) {
		foldOpaqueBranches(method);
	}

	protected void foldOpaqueBranches(@NotNull IrMethod method) {
		Map<Integer, IrBlock> blockByOffset = new HashMap<>();
		for (IrBlock block : method.blocks())
			blockByOffset.put(block.startOffset(), block);
		boolean changed = false;
		for (IrBlock block : method.blocks()) {
			IrTerminator terminator = block.terminator();
			if (terminator == null)
				continue;
			if (terminator.payload() instanceof BranchInstruction branch) {
				List<IrValue> inputs = terminator.inputs();
				IrValue left = inputs.get(0).canonical();
				IrValue right = inputs.get(1).canonical();
				Boolean constant = evaluateBranch(branch.opcode(), left, right);
				if (constant != null) {
					IrBlock trueTarget = blockByOffset.get(branch.label().position());
					IrBlock falseTarget = fallthroughTarget(method, block, trueTarget);
					IrBlock liveTarget = constant ? trueTarget : falseTarget;
					if (liveTarget != null) {
						replaceConditionalWithGoto(block, liveTarget);
						changed = true;
					}
				}
			} else if (terminator.payload() instanceof BranchZeroInstruction branch) {
				IrValue value = terminator.inputs().getFirst().canonical();
				Boolean constant = evaluateBranchZero(branch.opcode(), value);
				if (constant != null) {
					IrBlock trueTarget = blockByOffset.get(branch.label().position());
					IrBlock falseTarget = fallthroughTarget(method, block, trueTarget);
					IrBlock liveTarget = constant ? trueTarget : falseTarget;
					if (liveTarget != null) {
						replaceConditionalWithGoto(block, liveTarget);
						changed = true;
					}
				}
			}
		}
		if (changed) {
			Set<IrBlock> reachable = reachableBlocks(method);
			neutralizeUnreachableBlocks(method, reachable);
			pruneInvalidPredecessors(method, reachable);
		}
	}

	protected void replaceConditionalWithGoto(@NotNull IrBlock block, @NotNull IrBlock target) {
		for (IrBlock successor : new ArrayList<>(block.successors())) {
			if (successor == target)
				continue;
			removeIncomingEdge(block, successor);
		}
		block.successors().clear();
		block.addSuccessor(target, false);
		block.terminator(new IrTerminator(IrTerminatorKind.GOTO, List.of(), null));
	}

	protected void neutralizeUnreachableBlocks(@NotNull IrMethod method, @NotNull Set<IrBlock> reachable) {
		for (IrBlock block : method.blocks()) {
			if (reachable.contains(block))
				continue;
			for (IrBlock successor : new ArrayList<>(block.successors())) {
				removeIncomingEdge(block, successor);
			}
			for (IrBlock successor : new ArrayList<>(block.exceptionalSuccessors())) {
				removeIncomingEdge(block, successor);
			}
			block.predecessors().clear();
			block.successors().clear();
			block.exceptionalSuccessors().clear();
			block.phis().clear();
			block.statements().clear();
			block.exceptionInputs().clear();
			block.dexInstructions().clear();
			if (block.terminator() != null) {
				block.terminator(new IrTerminator(IrTerminatorKind.GOTO, List.of(), null));
			}
		}
	}

	protected void pruneInvalidPredecessors(@NotNull IrMethod method, @NotNull Set<IrBlock> reachable) {
		for (IrBlock block : method.blocks()) {
			if (!reachable.contains(block))
				continue;
			block.predecessors().removeIf(predecessor -> !reachable.contains(predecessor)
					|| (!predecessor.successors().contains(block) && !predecessor.exceptionalSuccessors().contains(block)));
			block.exceptionInputs().keySet().removeIf(predecessor -> !block.predecessors().contains(predecessor));
			for (IrPhi phi : block.phis()) {
				phi.operands().keySet().removeIf(predecessor -> !block.predecessors().contains(predecessor));
			}
		}
	}

	protected void removeIncomingEdge(@NotNull IrBlock predecessor, @NotNull IrBlock successor) {
		successor.predecessors().remove(predecessor);
		successor.exceptionInputs().remove(predecessor);
		for (IrPhi phi : successor.phis()) {
			phi.operands().remove(predecessor);
		}
	}

	protected @NotNull Set<IrBlock> reachableBlocks(@NotNull IrMethod method) {
		Set<IrBlock> reachable = new HashSet<>();
		collectReachable(method.entry(), reachable);
		return reachable;
	}

	protected void collectReachable(@NotNull IrBlock block, @NotNull Set<IrBlock> reachable) {
		if (!reachable.add(block))
			return;
		for (IrBlock successor : block.successors())
			collectReachable(successor, reachable);
		for (IrBlock successor : block.exceptionalSuccessors())
			collectReachable(successor, reachable);
	}

	protected @Nullable IrBlock fallthroughTarget(@NotNull IrMethod method, @NotNull IrBlock block, IrBlock branchTarget) {
		for (IrBlock successor : block.successors())
			if (successor != branchTarget)
				return successor;
		IrBlock next = nextBlock(method, block);
		return next == branchTarget ? next : null;
	}

	protected @Nullable IrBlock nextBlock(@NotNull IrMethod method, @NotNull IrBlock block) {
		int nextIndex = block.index() + 1;
		return nextIndex < method.blocks().size() ? method.blocks().get(nextIndex) : null;
	}


	protected static Boolean evaluateBranch(int opcode, @NotNull IrValue left, @NotNull IrValue right) {
		Object lv = left.constantValue();
		Object rv = right.constantValue();
		if (lv == null && rv == null && (left.isZeroConstant() || right.isZeroConstant())) {
			return switch (opcode) {
				case Opcodes.IF_EQ -> true;
				case Opcodes.IF_NE -> false;
				default -> null;
			};
		}
		if (!(lv instanceof Number ln) || !(rv instanceof Number rn)) return null;
		return switch (opcode) {
			case Opcodes.IF_EQ -> ln.intValue() == rn.intValue();
			case Opcodes.IF_NE -> ln.intValue() != rn.intValue();
			case Opcodes.IF_LT -> ln.intValue() < rn.intValue();
			case Opcodes.IF_GE -> ln.intValue() >= rn.intValue();
			case Opcodes.IF_GT -> ln.intValue() > rn.intValue();
			case Opcodes.IF_LE -> ln.intValue() <= rn.intValue();
			default -> null;
		};
	}

	protected static Boolean evaluateBranchZero(int opcode, @NotNull IrValue value) {
		Object constant = value.constantValue();
		if (!(constant instanceof Number number)) {
			return value.isZeroConstant() ? opcode == Opcodes.IF_EQZ : null;
		}
		return switch (opcode) {
			case Opcodes.IF_EQZ -> number.intValue() == 0;
			case Opcodes.IF_NEZ -> number.intValue() != 0;
			case Opcodes.IF_LTZ -> number.intValue() < 0;
			case Opcodes.IF_GEZ -> number.intValue() >= 0;
			case Opcodes.IF_GTZ -> number.intValue() > 0;
			case Opcodes.IF_LEZ -> number.intValue() <= 0;
			default -> null;
		};
	}
}
