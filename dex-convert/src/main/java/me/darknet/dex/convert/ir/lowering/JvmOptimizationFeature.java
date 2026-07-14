package me.darknet.dex.convert.ir.lowering;

/**
 * Independently gateable aggressive JVM lowering features.
 *
 * <p>This is intentionally package-private.  Feature selection is an
 * implementation and test concern; the public lowering policy remains the
 * three-level {@link JvmLoweringPolicy} API.</p>
 */
enum JvmOptimizationFeature {
	CLEANUP_REGIONS,
	CLEANUP_TAILS,
	SINGLE_USE_INLINE,
	MONITOR_REGIONS,
	LOOP_RESTRUCTURE,
	RECEIVER_CHAINS
}
