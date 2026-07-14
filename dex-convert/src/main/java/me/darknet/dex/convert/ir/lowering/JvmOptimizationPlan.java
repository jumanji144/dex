package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrExceptionRegion;
import me.darknet.dex.convert.ir.IrMethod;
import org.jetbrains.annotations.NotNull;

/**
 * Shared policy-aware orchestration for optional JVM transformations.  The
 * proof implementation remains centralized in {@link JvmOptimizationGuards};
 * this class selects the proof strength and gives the emitter one decision
 * model for guarded and aggressive modes.
 */
final class JvmOptimizationPlan {
	private final JvmLoweringPolicy policy;
	private final JvmOptimizationGuards guards;
	private final JvmOptimizationFeatures features;

	JvmOptimizationPlan(@NotNull IrMethod method, @NotNull JvmLoweringPolicy policy,
	                    @NotNull LoweringUseGraph useGraph) {
		this(method, policy, useGraph, JvmOptimizationFeatures.defaultFor(policy));
	}

	JvmOptimizationPlan(@NotNull IrMethod method, @NotNull JvmLoweringPolicy policy,
	                    @NotNull LoweringUseGraph useGraph,
	                    @NotNull JvmOptimizationFeatures features) {
		this(method, policy, useGraph, features, JvmLoweringFacts.analyze(method, useGraph));
	}

	JvmOptimizationPlan(@NotNull IrMethod method, @NotNull JvmLoweringPolicy policy,
	                    @NotNull LoweringUseGraph useGraph,
	                    @NotNull JvmOptimizationFeatures features,
	                    @NotNull JvmLoweringFacts facts) {
		this.policy = policy;
		this.guards = new JvmOptimizationGuards(method, useGraph, facts);
		this.features = features;
	}

	/**
	 * Returns whether the feature is available to aggressive lowering.  The
	 * non-aggressive policies deliberately ignore the internal feature set so
	 * test-only gate state cannot change their established behavior.
	 */
	boolean featureEnabled(@NotNull JvmOptimizationFeature feature) {
		return !policy.aggressiveCleanup() || features.contains(feature);
	}

	@NotNull JvmOptimizationFeatures features() {
		return features;
	}

	JvmOptimizationDecision resourceRegion(@NotNull IrExceptionRegion region) {
		return resourceRegion(region, null);
	}

	JvmOptimizationDecision resourceRegion(@NotNull IrExceptionRegion region,
	                                       @org.jetbrains.annotations.Nullable JvmCleanupRegionPlan cleanupPlan) {
		int offset = region.startOffset();
		if (!policy.optimized())
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.CLEANUP_REGIONS,
					"resource-region", offset, "policy keeps local-first lowering");
		if (policy.aggressiveCleanup() && !features.contains(JvmOptimizationFeature.CLEANUP_REGIONS))
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.CLEANUP_REGIONS,
					"resource-region", offset, "feature gate disabled");
		if (guards.allowResourceRegion(region))
			return JvmOptimizationDecision.guarded(JvmOptimizationFeature.CLEANUP_REGIONS, "resource-region", offset,
					"strict exceptional-state proof");
		if (policy.aggressiveCleanup() && cleanupPlan != null
				&& guards.allowAggressiveResourceRegion(region, cleanupPlan))
			return JvmOptimizationDecision.aggressive(JvmOptimizationFeature.CLEANUP_REGIONS,
					"resource-region", offset,
					"relaxed exceptional-state proof with materialized cleanup state");
		return JvmOptimizationDecision.rejected(JvmOptimizationFeature.CLEANUP_REGIONS, "resource-region", offset,
				"protected cleanup state is not materializable");
	}

	JvmOptimizationDecision cleanupTail(@NotNull JvmCleanupTailCandidate candidate,
	                                    boolean proofComplete) {
		if (!policy.aggressiveCleanup())
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.CLEANUP_TAILS, "cleanup-tail", candidate.sourceOffset(),
					"policy bypasses aggressive cleanup-tail normalization");
		if (!features.contains(JvmOptimizationFeature.CLEANUP_TAILS))
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.CLEANUP_TAILS, "cleanup-tail", candidate.sourceOffset(),
					"feature gate disabled");
		if (!proofComplete)
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.CLEANUP_TAILS, "cleanup-tail", candidate.sourceOffset(),
					"tail arguments or exception coverage are not materializable");
		return JvmOptimizationDecision.aggressive(JvmOptimizationFeature.CLEANUP_TAILS, "cleanup-tail", candidate.sourceOffset(),
				"canonical cleanup effects and terminal outcome are equivalent");
	}

	JvmOptimizationDecision singleUse(@NotNull JvmSingleUseCandidate candidate) {
		if (candidate.mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN)
			return receiverChain(candidate);
		if (!policy.aggressiveCleanup())
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.SINGLE_USE_INLINE,
					"single-use-" + candidate.mode().name().toLowerCase(),
					candidate.sourceOffset(), "policy keeps guarded/local lowering");
		if (!features.contains(JvmOptimizationFeature.SINGLE_USE_INLINE))
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.SINGLE_USE_INLINE,
					"single-use-" + candidate.mode().name().toLowerCase(),
					candidate.sourceOffset(), "feature gate disabled");
		if (!candidate.proofEligible())
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.SINGLE_USE_INLINE,
					"single-use-" + candidate.mode().name().toLowerCase(),
					candidate.sourceOffset(), candidate.proofReason());
		return JvmOptimizationDecision.aggressive(JvmOptimizationFeature.SINGLE_USE_INLINE,
				"single-use-" + candidate.mode().name().toLowerCase(),
				candidate.sourceOffset(), "single-use producer/consumer proof");
	}

	JvmOptimizationDecision receiverChain(@NotNull JvmSingleUseCandidate candidate) {
		if (!policy.optimized())
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.RECEIVER_CHAINS, "receiver-chain", candidate.sourceOffset(),
					"policy keeps local-first receiver materialization");
		if (!candidate.proofEligible())
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.RECEIVER_CHAINS, "receiver-chain", candidate.sourceOffset(),
					candidate.proofReason());
		if (!policy.aggressiveCleanup())
			return JvmOptimizationDecision.guarded(JvmOptimizationFeature.RECEIVER_CHAINS, "receiver-chain", candidate.sourceOffset(),
					"strict same-block receiver-chain proof");
		if (!features.contains(JvmOptimizationFeature.RECEIVER_CHAINS))
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.RECEIVER_CHAINS, "receiver-chain", candidate.sourceOffset(),
					"feature gate disabled");
		return JvmOptimizationDecision.aggressive(JvmOptimizationFeature.RECEIVER_CHAINS, "receiver-chain", candidate.sourceOffset(),
					"receiver/result, evaluation-order, and materialization proof");
	}

	JvmOptimizationDecision monitorRegion(@NotNull JvmMonitorRegionCandidate candidate) {
		if (!policy.aggressiveCleanup())
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.MONITOR_REGIONS, "monitor-region", candidate.sourceOffset(),
					"policy keeps monitor cleanup local-first");
		if (!features.contains(JvmOptimizationFeature.MONITOR_REGIONS))
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.MONITOR_REGIONS, "monitor-region", candidate.sourceOffset(),
					"feature gate disabled");
		if (!candidate.proofEligible())
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.MONITOR_REGIONS, "monitor-region", candidate.sourceOffset(),
					candidate.proofReason());
		return JvmOptimizationDecision.aggressive(JvmOptimizationFeature.MONITOR_REGIONS, "monitor-region", candidate.sourceOffset(),
				"equivalent monitor exits have materialized lock and cleanup state");
	}

	JvmOptimizationDecision loopShape(@NotNull JvmLoopShapeCandidate candidate) {
		if (!policy.loopRestructuring())
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.LOOP_RESTRUCTURE,
					"loop-" + candidate.kind().name().toLowerCase(),
					candidate.sourceOffset(), "policy keeps canonical loop layout");
		if (policy.aggressiveCleanup() && !features.contains(JvmOptimizationFeature.LOOP_RESTRUCTURE))
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.LOOP_RESTRUCTURE,
					"loop-" + candidate.kind().name().toLowerCase(),
					candidate.sourceOffset(), "feature gate disabled");
		if (!candidate.proofEligible())
			return JvmOptimizationDecision.rejected(JvmOptimizationFeature.LOOP_RESTRUCTURE,
					"loop-" + candidate.kind().name().toLowerCase(),
					candidate.sourceOffset(), candidate.proofReason());
		return JvmOptimizationDecision.aggressive(JvmOptimizationFeature.LOOP_RESTRUCTURE,
				"loop-" + candidate.kind().name().toLowerCase(),
				candidate.sourceOffset(), "natural-loop, branch, and local-state proof");
	}
}
