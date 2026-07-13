package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Exception-region shape analysis used while constructing JVM handlers.
 */
final class IrExceptionEmitter {
	private IrExceptionEmitter() {}

	static boolean isSyntheticRethrowRegion(@NotNull IrMethod method,
	                                        @NotNull IrExceptionRegion region,
	                                        @NotNull IrExceptionHandler exceptionHandler) {
		IrBlock handlerBlock = exceptionHandler.handlerBlock();
		boolean hasRethrow = region.protectedBlocks().stream()
				.anyMatch(block -> block.terminator() != null && block.terminator().kind() == IrTerminatorKind.THROW);
		if (!hasRethrow) return false;
		boolean continuesHandler = region.protectedBlocks().stream()
				.anyMatch(block -> block.predecessors().stream()
						.anyMatch(predecessor -> predecessor.exceptionValue() != null));
		if (!continuesHandler) return false;
		return method.exceptionRegions().stream()
				.anyMatch(other -> other != region && other.startOffset() < region.startOffset()
						&& other.handlers().stream().anyMatch(handler -> handler.handlerBlock() == handlerBlock));
	}

	static boolean isRedundantNullResourceRegion(@NotNull IrExceptionRegion region,
	                                             @NotNull IrExceptionHandler exceptionHandler,
	                                             @NotNull Map<Integer, IrBlock> blockByOffset) {
		if (region.protectedBlocks().stream().noneMatch(block -> block.terminator() != null
				&& block.terminator().kind() == IrTerminatorKind.THROW)) return false;
		IrBlock handlerBlock = exceptionHandler.handlerBlock();
		IrTerminator handlerTerminator = handlerBlock.terminator();
		if (handlerTerminator == null || handlerTerminator.kind() != IrTerminatorKind.GOTO) return false;
		IrBlock cleanup = gotoTarget(handlerBlock, handlerTerminator.payload(), blockByOffset);
		if (cleanup == null || cleanup.terminator() == null
				|| cleanup.terminator().kind() != IrTerminatorKind.IF_ZERO
				|| cleanup.terminator().inputs().isEmpty()) return false;
		IrValue resource = cleanup.terminator().inputs().getFirst();
		if (!(resource instanceof IrPhi phi)) return false;
		Set<IrBlock> protectedBlocks = new HashSet<>(region.protectedBlocks());
		Set<IrValue> resourceInputs = new HashSet<>();
		for (IrValue input : phi.operands().values()) resourceInputs.add(input.canonical());
		for (IrBlock block : region.protectedBlocks()) {
			for (IrBlock predecessor : block.predecessors()) {
				if (protectedBlocks.contains(predecessor)) continue;
				IrTerminator terminator = predecessor.terminator();
				if (terminator == null || terminator.kind() != IrTerminatorKind.IF_ZERO
						|| terminator.inputs().isEmpty()
						|| !resourceInputs.contains(terminator.inputs().getFirst().canonical())) return false;
				BranchZeroInstruction branch = (BranchZeroInstruction) terminator.payload();
				if (blockByOffset.get(branch.label().position()) != block) return false;
			}
		}
		return true;
	}

	private static IrBlock gotoTarget(@NotNull IrBlock block, Object payload,
	                                  @NotNull Map<Integer, IrBlock> blockByOffset) {
		if (payload instanceof GotoInstruction gotoInstruction)
			return blockByOffset.get(gotoInstruction.jump().position());
		return block.successors().isEmpty() ? null : block.successors().getFirst();
	}
}

