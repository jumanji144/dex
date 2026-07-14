package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/** Policy decision and lowering-only layout information for a loop shape. */
record JvmLoopShapePlan(
		@NotNull JvmLoopShapeCandidate candidate,
		@NotNull List<IrBlock> emissionOrder,
		@NotNull Map<IrBlock, IrBlock> preferredFallthrough,
		@NotNull Map<IrBlock, Boolean> branchInverted,
		@NotNull Map<IrValue, IrValue> loopCarriedMappings,
		@NotNull JvmOptimizationDecision decision) {
	JvmLoopShapePlan {
		emissionOrder = List.copyOf(emissionOrder);
		preferredFallthrough = Map.copyOf(preferredFallthrough);
		branchInverted = Map.copyOf(branchInverted);
		loopCarriedMappings = Map.copyOf(loopCarriedMappings);
	}

	boolean accepted() {
		return decision.accepted();
	}
}
