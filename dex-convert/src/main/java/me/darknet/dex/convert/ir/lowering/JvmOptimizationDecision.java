package me.darknet.dex.convert.ir.lowering;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

/** Immutable result of a shared JVM optimization proof. */
record JvmOptimizationDecision(
		String feature,
		@Nullable JvmOptimizationFeature gate,
		boolean accepted,
		JvmProofStrength strength,
		int sourceOffset,
		String reason) {
	static JvmOptimizationDecision rejected(String feature, int sourceOffset, String reason) {
		return new JvmOptimizationDecision(feature, null, false, JvmProofStrength.NONE, sourceOffset, reason);
	}

	static JvmOptimizationDecision guarded(String feature, int sourceOffset, String reason) {
		return new JvmOptimizationDecision(feature, null, true, JvmProofStrength.GUARDED, sourceOffset, reason);
	}

	static JvmOptimizationDecision aggressive(String feature, int sourceOffset, String reason) {
		return new JvmOptimizationDecision(feature, null, true, JvmProofStrength.AGGRESSIVE, sourceOffset, reason);
	}

	static JvmOptimizationDecision rejected(@NotNull JvmOptimizationFeature gate, String feature,
	                                       int sourceOffset, String reason) {
		return new JvmOptimizationDecision(feature, gate, false, JvmProofStrength.NONE, sourceOffset, reason);
	}

	static JvmOptimizationDecision guarded(@NotNull JvmOptimizationFeature gate, String feature,
	                                      int sourceOffset, String reason) {
		return new JvmOptimizationDecision(feature, gate, true, JvmProofStrength.GUARDED, sourceOffset, reason);
	}

	static JvmOptimizationDecision aggressive(@NotNull JvmOptimizationFeature gate, String feature,
	                                         int sourceOffset, String reason) {
		return new JvmOptimizationDecision(feature, gate, true, JvmProofStrength.AGGRESSIVE, sourceOffset, reason);
	}

	boolean relaxed() {
		return strength == JvmProofStrength.AGGRESSIVE;
	}
}
