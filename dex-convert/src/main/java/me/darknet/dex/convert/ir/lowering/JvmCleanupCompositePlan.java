package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionRegion;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrParameter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable description of one aggressively composable resource lifecycle.
 * The IR is deliberately not rewritten: this object only gives JVM layout a
 * common containment and handler-state proof.
 */
record JvmCleanupCompositePlan(
		@NotNull JvmCleanupRegionPlan outer,
		@NotNull List<JvmCleanupRegionPlan> layers,
		@NotNull Map<IrExceptionRegion, JvmCleanupHandlerRole> handlerRoles,
		boolean accepted,
		@NotNull String reason) {
	JvmCleanupCompositePlan {
		layers = List.copyOf(layers);
		Map<IrExceptionRegion, JvmCleanupHandlerRole> roles = new IdentityHashMap<>();
		roles.putAll(handlerRoles);
		handlerRoles = Collections.unmodifiableMap(roles);
	}

	static @NotNull JvmCleanupCompositePlan rejected(@NotNull JvmCleanupRegionPlan outer,
	                                                 @NotNull String reason) {
		return new JvmCleanupCompositePlan(outer, List.of(outer), Map.of(), false, reason);
	}

	static @NotNull JvmCleanupCompositePlan discover(@NotNull IrMethod method,
	                                                 @NotNull JvmCleanupRegionPlan outer) {
		List<JvmCleanupRegionPlan> layers = new ArrayList<>();
		flatten(outer, layers);
		return discoverLayers(method, outer, layers);
	}

	/**
	 * Composes lifecycle candidates whose DEX protected intervals have been
	 * split into adjacent fragments.  The DEX frontend is allowed to split a
	 * resource scope at every throwing instruction, so interval containment is
	 * not by itself evidence that the JVM resources are unrelated.  Acquisition
	 * and reverse-close ordering is the stronger invariant for composing the
	 * layers; candidates sharing the same canonical resource are collapsed to
	 * the widest proven lifecycle first.
	 */
	static @NotNull JvmCleanupCompositePlan discoverOrdered(@NotNull IrMethod method,
	                                                       @NotNull List<JvmCleanupRegionPlan> candidates) {
		if (candidates.isEmpty())
			throw new IllegalArgumentException("empty cleanup candidate list");
		// Cleanup candidates originate in identity-based maps.  Establish a
		// source-derived order before collapsing equivalent acquisition/close
		// fragments; otherwise two conversions of the same DEX method can pick
		// different representatives and consequently different JVM layouts.
		List<JvmCleanupRegionPlan> orderedCandidates = new ArrayList<>(candidates);
		orderedCandidates.sort(stableOrder(method));
		List<JvmCleanupRegionPlan> unique = new ArrayList<>();
		for (JvmCleanupRegionPlan candidate : orderedCandidates) {
			if (candidate.resource() == null) continue;
			int existing = -1;
			for (int index = 0; index < unique.size(); index++) {
				JvmCleanupRegionPlan previous = unique.get(index);
				if (previous.acquisition() == candidate.acquisition()
						&& previous.normalClose() == candidate.normalClose()) {
					existing = index;
					break;
				}
			}
			if (existing < 0) unique.add(candidate);
			else if (widerLifecycle(method, candidate, unique.get(existing))) unique.set(existing, candidate);
		}
		unique.sort(stableOrder(method));
		JvmCleanupRegionPlan outer = unique.isEmpty() ? orderedCandidates.getFirst() : unique.getFirst();
		return discoverLayers(method, outer, unique);
	}

	private static boolean widerLifecycle(@NotNull IrMethod method,
	                                     @NotNull JvmCleanupRegionPlan candidate,
	                                     @NotNull JvmCleanupRegionPlan previous) {
		int candidateAcquire = candidate.acquisitionOffset(method);
		int previousAcquire = previous.acquisitionOffset(method);
		int candidateClose = candidate.normalCloseOffset(method);
		int previousClose = previous.normalCloseOffset(method);
		return candidateAcquire == previousAcquire && candidateClose > previousClose;
	}

	private static @NotNull JvmCleanupCompositePlan discoverLayers(@NotNull IrMethod method,
	                                                               @NotNull JvmCleanupRegionPlan outer,
	                                                               @NotNull List<JvmCleanupRegionPlan> candidates) {
		List<JvmCleanupRegionPlan> layers = new ArrayList<>();
		layers.add(outer);
		List<JvmCleanupRegionPlan> ordered = new ArrayList<>(candidates);
		ordered.remove(outer);
		ordered.sort(stableOrder(method));
		int previousAcquire = outer.acquisitionOffset(method);
		int previousClose = outer.normalCloseOffset(method);
		Set<IrExceptionRegion> regions = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		regions.add(outer.region());
		for (JvmCleanupRegionPlan layer : ordered) {
			int acquire = layer.acquisitionOffset(method);
			int close = layer.normalCloseOffset(method);
			boolean sharedBoundary = acquire == previousAcquire
					&& layers.getLast().resource().canonical() instanceof IrParameter;
			if (acquire < 0 || close < 0 || (!sharedBoundary && acquire <= previousAcquire)
					|| close >= previousClose)
				continue;
			boolean parameterOwned = layer.resource().canonical() instanceof IrParameter;
			if (!layer.hasMaterializedResource() || (!parameterOwned && layer.acquisition() == null)
					|| layer.normalClose() == null
					|| !regions.add(layer.region())) continue;
			layers.add(layer);
			previousAcquire = acquire;
			previousClose = close;
		}
		if (layers.size() < 2)
			return rejected(outer, "nested lifecycle has fewer than two proven layers");

		Map<IrExceptionRegion, JvmCleanupHandlerRole> roles = new IdentityHashMap<>();
		for (int index = 0; index < layers.size(); index++) {
			JvmCleanupRegionPlan layer = layers.get(index);
			boolean parameterOwned = layer.resource().canonical() instanceof IrParameter;
			if (!layer.hasMaterializedResource()
					|| (!parameterOwned && layer.acquisition() == null) || layer.normalClose() == null)
				return rejected(outer, "resource identity or acquisition/close proof is incomplete");
			int acquire = layer.acquisitionOffset(method);
			int close = layer.normalCloseOffset(method);
			if (acquire < 0 || close < 0 || acquire >= close)
				return rejected(outer, "resource layers are not strictly nested in reverse-close order");
			roles.put(layer.region(), role(layer));
		}
		return new JvmCleanupCompositePlan(outer, layers, roles, true,
				"nested acquisition, reverse-close, and handler-state proof");
	}

	private static @NotNull Comparator<JvmCleanupRegionPlan> stableOrder(@NotNull IrMethod method) {
		return Comparator.comparingInt((JvmCleanupRegionPlan plan) -> plan.acquisitionOffset(method))
				.thenComparing(plan -> plan.resource().canonical() instanceof IrParameter ? 0 : 1)
				.thenComparingInt(plan -> plan.normalCloseOffset(method))
				.thenComparingInt(plan -> plan.region().startOffset())
				.thenComparingInt(plan -> plan.region().endOffset())
				.thenComparingInt(plan -> plan.handler().handlerBlock().startOffset())
				.thenComparing(plan -> plan.resource().canonical().type().descriptor());
	}

	private static void flatten(@NotNull JvmCleanupRegionPlan plan,
	                           @NotNull List<JvmCleanupRegionPlan> output) {
		output.add(plan);
		List<JvmCleanupRegionPlan> children = new ArrayList<>(plan.directNestedLayers());
		children.sort(Comparator.comparingInt(child -> child.region().startOffset()));
		for (JvmCleanupRegionPlan child : children) flatten(child, output);
	}

	private static @NotNull JvmCleanupHandlerRole role(@NotNull JvmCleanupRegionPlan plan) {
		if (plan.hasSuppressedExceptionPath()) return JvmCleanupHandlerRole.SUPPRESSED_RETHROW;
		if (plan.normalClose() != null) return JvmCleanupHandlerRole.RESOURCE_CLOSE;
		IrBlock handler = plan.handler().handlerBlock();
		if (handler.exceptionValue() == null && handler.terminator() != null
				&& handler.terminator().kind() == IrTerminatorKind.RETURN)
			return JvmCleanupHandlerRole.FINALLY_CLEANUP;
		return JvmCleanupHandlerRole.OUTER_FAILURE;
	}

	private static int operationOffset(@NotNull IrMethod method, @NotNull me.darknet.dex.convert.ir.statement.IrOp op) {
		for (IrBlock block : method.blocks())
			if (block.statements().contains(op)) return block.startOffset();
		return -1;
	}

	private static boolean contains(@NotNull IrExceptionRegion outer, @NotNull IrExceptionRegion inner) {
		return outer.startOffset() <= inner.startOffset() && outer.endOffset() >= inner.endOffset();
	}

	private static boolean overlaps(@NotNull IrExceptionRegion left, @NotNull IrExceptionRegion right) {
		return left.startOffset() < right.endOffset() && right.startOffset() < left.endOffset()
				&& !contains(left, right) && !contains(right, left);
	}
}
