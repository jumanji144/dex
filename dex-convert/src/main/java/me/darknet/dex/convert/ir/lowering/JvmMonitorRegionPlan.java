package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable decision and edge data for one shared monitor-exit tail. */
record JvmMonitorRegionPlan(
		@NotNull JvmMonitorRegionCandidate candidate,
		@NotNull IrBlock canonicalExit,
		@NotNull List<IrBlock> duplicateExits,
		@NotNull Map<IrBlock, Map<IrValue, IrValue>> edgeMappings,
		@NotNull JvmOptimizationDecision decision) {
	JvmMonitorRegionPlan {
		duplicateExits = List.copyOf(duplicateExits);
		Map<IrBlock, Map<IrValue, IrValue>> copied = new LinkedHashMap<>();
		edgeMappings.forEach((block, mapping) -> copied.put(block,
				Collections.unmodifiableMap(new LinkedHashMap<>(mapping))));
		edgeMappings = Collections.unmodifiableMap(copied);
	}

	boolean accepted() {
		return decision.accepted();
	}

	static @NotNull JvmMonitorRegionPlan rejected(@NotNull JvmMonitorRegionCandidate candidate,
	                                             @NotNull JvmOptimizationDecision decision) {
		return new JvmMonitorRegionPlan(candidate, candidate.enterBlock(), List.of(), Map.of(), decision);
	}
}
