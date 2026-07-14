package me.darknet.dex.convert.ir.lowering;

/** JVM lowering policy. The local path is always the correctness authority. */
public enum JvmLoweringPolicy {
	DETERMINISTIC_LOCAL(false, false, false, false, false),

	/** Enables proof-guarded, same-block expression optimizations. */
	GUARDED_OPTIMIZED(true, true, false, false, false),

	/**
	 * Enables explicitly opt-in cleanup and exception-region shaping whose
	 * semantic proof is weaker than the guarded policy. JVM stack/type checks
	 * and class verification remain mandatory.
	 */
	AGGRESSIVE_OPTIMIZED(true, true, true, true, true);

	private final boolean optimized;
	private final boolean guardedExpressions;
	private final boolean aggressiveCleanup;
	private final boolean taintsRelaxedProofs;
	private final boolean loopRestructuring;

	JvmLoweringPolicy(boolean optimized, boolean guardedExpressions,
 	                 boolean aggressiveCleanup, boolean taintsRelaxedProofs,
 	                 boolean loopRestructuring) {
		this.optimized = optimized;
		this.guardedExpressions = guardedExpressions;
		this.aggressiveCleanup = aggressiveCleanup;
		this.taintsRelaxedProofs = taintsRelaxedProofs;
		this.loopRestructuring = loopRestructuring;
	}

	public boolean optimized() {
		return optimized;
	}

	public boolean guardedExpressions() {
		return guardedExpressions;
	}

	public boolean aggressiveCleanup() {
		return aggressiveCleanup;
	}

	public boolean taintsRelaxedProofs() {
		return taintsRelaxedProofs;
	}

	/** Whether lowering may apply proof-driven loop layout and branch shaping. */
	public boolean loopRestructuring() {
		return loopRestructuring;
	}
}
