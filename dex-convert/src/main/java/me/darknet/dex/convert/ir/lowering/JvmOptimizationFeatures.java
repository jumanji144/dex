package me.darknet.dex.convert.ir.lowering;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Immutable, package-private feature selection for one lowering state. */
final class JvmOptimizationFeatures {
	private final Set<JvmOptimizationFeature> enabled;

	private JvmOptimizationFeatures(@NotNull Set<JvmOptimizationFeature> enabled) {
		EnumSet<JvmOptimizationFeature> copy = EnumSet.noneOf(JvmOptimizationFeature.class);
		copy.addAll(enabled);
		this.enabled = Collections.unmodifiableSet(copy);
	}

	static @NotNull JvmOptimizationFeatures all() {
		return new JvmOptimizationFeatures(EnumSet.allOf(JvmOptimizationFeature.class));
	}

	static @NotNull JvmOptimizationFeatures defaultFor(@NotNull JvmLoweringPolicy policy) {
		return policy == JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED ? all() : none();
	}

	static @NotNull JvmOptimizationFeatures none() {
		return new JvmOptimizationFeatures(EnumSet.noneOf(JvmOptimizationFeature.class));
	}

	boolean contains(@NotNull JvmOptimizationFeature feature) {
		return enabled.contains(feature);
	}

	@NotNull JvmOptimizationFeatures withEnabled(@NotNull JvmOptimizationFeature feature) {
		EnumSet<JvmOptimizationFeature> copy = EnumSet.noneOf(JvmOptimizationFeature.class);
		copy.addAll(enabled);
		copy.add(feature);
		return new JvmOptimizationFeatures(copy);
	}

	@NotNull JvmOptimizationFeatures withDisabled(@NotNull JvmOptimizationFeature feature) {
		EnumSet<JvmOptimizationFeature> copy = EnumSet.noneOf(JvmOptimizationFeature.class);
		copy.addAll(enabled);
		copy.remove(feature);
		return new JvmOptimizationFeatures(copy);
	}

	@NotNull Set<JvmOptimizationFeature> enabled() {
		return enabled;
	}
}
