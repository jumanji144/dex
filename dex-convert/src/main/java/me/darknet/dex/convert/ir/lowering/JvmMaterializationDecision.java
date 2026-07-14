package me.darknet.dex.convert.ir.lowering;

import org.jetbrains.annotations.Nullable;

/** Immutable materialization choice made by the lowering plan. */
record JvmMaterializationDecision(
		JvmMaterializationKind kind,
		@Nullable JvmSingleUsePlan singleUsePlan,
		String reason) {
	boolean inline() {
		return kind == JvmMaterializationKind.SINGLE_USE_INLINE
				|| kind == JvmMaterializationKind.RECEIVER_CHAIN;
	}
}
