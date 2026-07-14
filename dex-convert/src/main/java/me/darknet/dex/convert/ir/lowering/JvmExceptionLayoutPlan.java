package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionHandler;
import me.darknet.dex.convert.ir.IrExceptionRegion;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of JVM exception/control-flow layout decisions.  Labels
 * are created by the lowering layout before this object is captured; this
 * class only owns their relationships and never re-discovers IR lifecycle
 * patterns during bytecode serialization.
 */
final class JvmExceptionLayoutPlan {
	record PrimaryExceptionState(
			@NotNull IrValue value,
			@NotNull IrBlock entry,
			int local,
			@NotNull Label afterStore) {}

	record PlannedRange(
			@NotNull IrExceptionRegion region,
			@NotNull IrExceptionHandler handler,
			@Nullable JvmCleanupRegionPlan lifecycle,
			@NotNull IrBlock firstSource,
			@NotNull IrBlock lastSource,
			@NotNull Label end,
			@NotNull Label handlerLabel,
			@Nullable String catchType,
			@Nullable JvmCleanupHandlerRole handlerRole,
			boolean usesHandlerStub,
			boolean relocated,
			boolean coalesced,
			boolean suppressed,
			@Nullable PrimaryExceptionState primaryExceptionState) {}

	private final Map<IrBlock, Label> labels;
	private final Map<JvmHandlerStubKey, Label> handlerStubs;
	private final Map<IrBlock, Label> simpleHandlerStubs;
	private final Map<IrBlock, Label> handlerEntries;
	private final Map<IrExceptionRegion, Label> tryStarts;
	private final Map<IrBlock, Label> tryStartsByBlock;
	private final Map<IrBlock, Label> protectedBoundaries;
	private final Map<IrExceptionRegion, JvmCleanupRegionPlan> cleanupRegions;
	private final Map<IrExceptionRegion, JvmCleanupRegionPlan> protectedRangePlans;
	private final Set<IrBlock> skippedBlocks;
	private final Map<IrBlock, List<IrBlock>> deferredNullThrowInsertions;
	private final Map<IrBlock, JvmCleanupTailPlan> cleanupTails;
	private final Map<IrBlock, JvmMonitorRegionPlan> monitorTails;
	private final List<PlannedRange> plannedRanges;

	private JvmExceptionLayoutPlan(@NotNull Map<IrBlock, Label> labels,
	                              @NotNull Map<JvmHandlerStubKey, Label> handlerStubs,
	                              @NotNull Map<IrBlock, Label> simpleHandlerStubs,
	                              @NotNull Map<IrBlock, Label> handlerEntries,
	                              @NotNull Map<IrExceptionRegion, Label> tryStarts,
	                              @NotNull Map<IrBlock, Label> tryStartsByBlock,
	                              @NotNull Map<IrBlock, Label> protectedBoundaries,
	                              @NotNull Map<IrExceptionRegion, JvmCleanupRegionPlan> cleanupRegions,
	                              @NotNull Map<IrExceptionRegion, JvmCleanupRegionPlan> protectedRangePlans,
	                              @NotNull Set<IrBlock> skippedBlocks,
	                              @NotNull Map<IrBlock, List<IrBlock>> deferredNullThrowInsertions,
	                              @NotNull Map<IrBlock, JvmCleanupTailPlan> cleanupTails,
	                              @NotNull Map<IrBlock, JvmMonitorRegionPlan> monitorTails,
	                              @NotNull List<PlannedRange> plannedRanges) {
		this.labels = identityMap(labels);
		this.handlerStubs = Collections.unmodifiableMap(new LinkedHashMap<>(handlerStubs));
		this.simpleHandlerStubs = identityMap(simpleHandlerStubs);
		this.handlerEntries = identityMap(handlerEntries);
		this.tryStarts = identityMap(tryStarts);
		this.tryStartsByBlock = identityMap(tryStartsByBlock);
		this.protectedBoundaries = identityMap(protectedBoundaries);
		this.cleanupRegions = identityMap(cleanupRegions);
		this.protectedRangePlans = identityMap(protectedRangePlans);
		this.skippedBlocks = identitySet(skippedBlocks);
		Map<IrBlock, List<IrBlock>> insertionCopy = new IdentityHashMap<>();
		deferredNullThrowInsertions.forEach((block, values) -> insertionCopy.put(block, List.copyOf(values)));
		this.deferredNullThrowInsertions = Collections.unmodifiableMap(insertionCopy);
		this.cleanupTails = identityMap(cleanupTails);
		this.monitorTails = identityMap(monitorTails);
		this.plannedRanges = List.copyOf(plannedRanges);
	}

	static @NotNull JvmExceptionLayoutPlan capture(
			@NotNull Map<IrBlock, Label> labels,
			@NotNull Map<JvmHandlerStubKey, Label> handlerStubs,
			@NotNull Map<IrBlock, Label> simpleHandlerStubs,
			@NotNull Map<IrBlock, Label> handlerEntries,
			@NotNull Map<IrExceptionRegion, Label> tryStarts,
			@NotNull Map<IrBlock, Label> tryStartsByBlock,
			@NotNull Map<IrBlock, Label> protectedBoundaries,
			@NotNull Map<IrExceptionRegion, JvmCleanupRegionPlan> cleanupRegions,
			@NotNull Map<IrExceptionRegion, JvmCleanupRegionPlan> protectedRangePlans,
			@NotNull Set<IrBlock> skippedBlocks,
			@NotNull Map<IrBlock, List<IrBlock>> deferredNullThrowInsertions,
			@NotNull Map<IrBlock, JvmCleanupTailPlan> cleanupTails,
			@NotNull Map<IrBlock, JvmMonitorRegionPlan> monitorTails,
			@NotNull List<PlannedRange> plannedRanges) {
		return new JvmExceptionLayoutPlan(labels, handlerStubs, simpleHandlerStubs, handlerEntries,
				tryStarts, tryStartsByBlock, protectedBoundaries, cleanupRegions, protectedRangePlans, skippedBlocks,
				deferredNullThrowInsertions, cleanupTails, monitorTails, plannedRanges);
	}

	boolean hasHandlerStub(@NotNull IrBlock source, @NotNull IrBlock target) {
		return handlerStubs.containsKey(new JvmHandlerStubKey(source, target));
	}

	boolean hasSimpleHandlerStub(@NotNull IrBlock target) {
		return simpleHandlerStubs.containsKey(target);
	}

	@Nullable Label handlerStub(@NotNull IrBlock source, @NotNull IrBlock target) {
		return handlerStubs.get(new JvmHandlerStubKey(source, target));
	}

	@NotNull Label handlerEntry(@NotNull IrBlock target) {
		Label label = handlerEntries.get(target);
		if (label != null) return label;
		label = labels.get(target);
		if (label == null) throw new IllegalStateException("Missing JVM handler label for " + target.debugName());
		return label;
	}

	@Nullable Label tryStart(@NotNull IrExceptionRegion region) {
		return tryStarts.get(region);
	}

	@Nullable Label tryStart(@NotNull IrBlock block) {
		return tryStartsByBlock.get(block);
	}

	@Nullable Label protectedBoundary(@NotNull IrBlock block) {
		return protectedBoundaries.get(block);
	}

	@Nullable JvmCleanupRegionPlan cleanupRegion(@NotNull IrExceptionRegion region) {
		return cleanupRegions.get(region);
	}

	@Nullable JvmCleanupRegionPlan protectedRangePlan(@NotNull IrExceptionRegion region) {
		return protectedRangePlans.get(region);
	}

	boolean skipped(@NotNull IrBlock block) {
		return skippedBlocks.contains(block);
	}

	@NotNull List<IrBlock> deferredNullThrowInsertions(@NotNull IrBlock block) {
		return deferredNullThrowInsertions.getOrDefault(block, List.of());
	}

	@Nullable JvmCleanupTailPlan cleanupTail(@NotNull IrBlock block) {
		return cleanupTails.get(block);
	}

	@Nullable JvmMonitorRegionPlan monitorTail(@NotNull IrBlock block) {
		return monitorTails.get(block);
	}

	@NotNull List<PlannedRange> plannedRanges() {
		return plannedRanges;
	}

	private static <K, V> Map<K, V> identityMap(@NotNull Map<K, V> source) {
		return Collections.unmodifiableMap(new IdentityHashMap<>(source));
	}

	private static <K> Set<K> identitySet(@NotNull Set<K> source) {
		Set<K> copy = Collections.newSetFromMap(new IdentityHashMap<>(source.size()));
		copy.addAll(source);
		return Collections.unmodifiableSet(copy);
	}
}
