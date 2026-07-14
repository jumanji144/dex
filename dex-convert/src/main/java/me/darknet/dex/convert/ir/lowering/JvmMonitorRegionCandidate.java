package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Immutable lowering-time facts for one explicit monitor region.  The model is
 * deliberately independent of JVM locals; locals are allocated later and are
 * only used as a proof that edge state can be materialized.
 */
record JvmMonitorRegionCandidate(
		@NotNull IrValue lock,
		@NotNull IrEffect enter,
		@NotNull List<IrEffect> exits,
		@NotNull IrBlock enterBlock,
		@NotNull List<IrBlock> protectedBlocks,
		@NotNull List<IrBlock> normalExitBlocks,
		@NotNull List<IrBlock> exceptionalExitBlocks,
		@NotNull List<IrExceptionEdge> exceptionalEdges,
		@NotNull List<String> exceptionRangeProfile,
		@NotNull List<String> nestingProfile,
		int sourceOffset,
		@NotNull String exitSignature,
		boolean proofEligible,
		@NotNull String proofReason) {
	JvmMonitorRegionCandidate {
		exits = List.copyOf(exits);
		protectedBlocks = List.copyOf(protectedBlocks);
		normalExitBlocks = List.copyOf(normalExitBlocks);
		exceptionalExitBlocks = List.copyOf(exceptionalExitBlocks);
		exceptionalEdges = List.copyOf(exceptionalEdges);
		exceptionRangeProfile = List.copyOf(exceptionRangeProfile);
		nestingProfile = List.copyOf(nestingProfile);
	}
}
