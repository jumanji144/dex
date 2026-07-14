package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Immutable decision and edge mappings for one shared aggressive tail. */
record JvmCleanupTailPlan(
		@NotNull JvmCleanupTailSignature signature,
		@NotNull JvmCleanupTailCandidate canonical,
		@NotNull List<JvmCleanupTailCandidate> duplicates,
		@NotNull Map<IrBlock, Map<IrValue, IrValue>> edgeMappings,
		@NotNull JvmOptimizationDecision decision) {
	JvmCleanupTailPlan {
		duplicates = List.copyOf(duplicates);
		Map<IrBlock, Map<IrValue, IrValue>> copied = new LinkedHashMap<>();
		edgeMappings.forEach((block, mapping) -> copied.put(block,
				Collections.unmodifiableMap(new LinkedHashMap<>(mapping))));
		edgeMappings = Collections.unmodifiableMap(copied);
	}

	boolean accepted() {
		return decision.accepted();
	}

	static @NotNull JvmCleanupTailPlan rejected(@NotNull JvmCleanupTailCandidate candidate,
	                                             @NotNull JvmOptimizationDecision decision) {
		return new JvmCleanupTailPlan(candidate.signature(), candidate, List.of(), Map.of(), decision);
	}
}
