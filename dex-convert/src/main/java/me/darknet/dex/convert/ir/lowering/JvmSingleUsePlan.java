package me.darknet.dex.convert.ir.lowering;

import org.jetbrains.annotations.NotNull;

/** Immutable result of an aggressive single-use proof. */
record JvmSingleUsePlan(
		@NotNull JvmSingleUseCandidate candidate,
		@NotNull JvmOptimizationDecision decision) {
	boolean accepted() {
		return decision.accepted();
	}
}
