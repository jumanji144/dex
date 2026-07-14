package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Immutable description of a natural loop considered by the JVM layout
 * optimizer.  The candidate contains facts only; it does not mutate IR or
 * decide whether a policy is allowed to apply it.
 */
record JvmLoopShapeCandidate(
		@NotNull JvmLoopShapeKind kind,
		@NotNull IrBlock preheader,
		@NotNull IrBlock header,
		@NotNull List<IrBlock> predicateBlocks,
		@NotNull List<IrBlock> loopBlocks,
		@NotNull IrBlock backedge,
		@NotNull IrBlock canonicalExit,
		@NotNull List<IrBlock> terminalExits,
		@NotNull List<IrPhi> phis,
		@NotNull List<IrValue> inductionValues,
		int sourceOffset,
		@NotNull List<String> protectedRangeProfile,
		@NotNull List<String> exceptionalEdgeProfile,
		boolean proofEligible,
		@NotNull String proofReason) {
	JvmLoopShapeCandidate {
		predicateBlocks = List.copyOf(predicateBlocks);
		loopBlocks = List.copyOf(loopBlocks);
		terminalExits = List.copyOf(terminalExits);
		phis = List.copyOf(phis);
		inductionValues = List.copyOf(inductionValues);
		protectedRangeProfile = List.copyOf(protectedRangeProfile);
		exceptionalEdgeProfile = List.copyOf(exceptionalEdgeProfile);
	}
}
