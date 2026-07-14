package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionRegion;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Immutable control-flow facts shared by JVM lowering proofs. */
record JvmBlockFacts(
		@NotNull IrBlock block,
		boolean handler,
		boolean transparent,
		@NotNull List<String> protectedRangeProfile,
		@NotNull List<String> exceptionalProfile,
		@NotNull List<String> resourceLayerProfile) {
	boolean protectedBlock() {
		return !protectedRangeProfile.isEmpty();
	}

	static @NotNull JvmBlockFacts of(@NotNull IrBlock block,
	                                @NotNull List<IrExceptionRegion> regions) {
		List<String> protectedProfile = regions.stream()
				.filter(region -> region.protectedBlocks().contains(block))
				.map(region -> region.startOffset() + "-" + region.endOffset())
				.sorted()
				.toList();
		List<String> exceptionalProfile = block.exceptionEdges().stream()
				.map(edge -> edge.handlerBlock().index() + ":" + edge.throwMask()
						+ ":" + edge.sourceOffset())
				.sorted()
				.toList();
		boolean transparent = block.statements().isEmpty() && block.phis().isEmpty()
				&& block.exceptionValue() == null && block.exceptionalSuccessors().isEmpty()
				&& block.terminator() != null && block.terminator().kind() == IrTerminatorKind.GOTO;
		List<String> resourceProfile = regions.stream()
				.filter(region -> region.protectedBlocks().contains(block))
				.filter(region -> region.handlers().stream().anyMatch(handler ->
						handler.handler() == null || handler.handler().isCatchAll()))
				.map(region -> region.startOffset() + "-" + region.endOffset())
				.sorted()
				.toList();
		return new JvmBlockFacts(block, block.exceptionValue() != null, transparent,
				protectedProfile, exceptionalProfile, resourceProfile);
	}
}
