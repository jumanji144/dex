package me.darknet.dex.convert.ir;

import me.darknet.dex.convert.ConversionDiagnostic;
import me.darknet.dex.convert.ir.value.IrType;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.IdentityHashMap;

/**
 * @param source
 * 		Input method that this IR was generated from.
 * @param registerCount
 * 		Number of registers used by the method.
 * @param blocks
 * 		List of basic blocks in the method, in no particular order.
 * @param entry
 * 		The entry block of the method, which is the first block to be executed when the method is called.
 * @param exceptionRegions
 * 		Ordered protected exception regions in the method.
 */
public record IrMethod(@NotNull MethodMember source,
                       int registerCount,
                       @NotNull List<IrBlock> blocks,
                       @NotNull IrBlock entry,
                       @NotNull List<IrExceptionRegion> exceptionRegions,
                       boolean tainted,
                       @NotNull List<ConversionDiagnostic> diagnostics,
                       @NotNull Map<IrBlock, Map<IrValue, IrType>> flowFacts) {
	public IrMethod {
		diagnostics = List.copyOf(diagnostics);
		Map<IrBlock, Map<IrValue, IrType>> copy = new IdentityHashMap<>();
		flowFacts.forEach((block, facts) -> copy.put(block, Map.copyOf(facts)));
		flowFacts = Collections.unmodifiableMap(copy);
	}

	public IrMethod(@NotNull MethodMember source, int registerCount, @NotNull List<IrBlock> blocks,
					@NotNull IrBlock entry, @NotNull List<IrExceptionRegion> exceptionRegions,
					boolean tainted, @NotNull List<ConversionDiagnostic> diagnostics) {
		this(source, registerCount, blocks, entry, exceptionRegions, tainted, diagnostics, Map.of());
	}

	public IrMethod(@NotNull MethodMember source, int registerCount, @NotNull List<IrBlock> blocks,
					@NotNull IrBlock entry, @NotNull List<IrExceptionRegion> exceptionRegions) {
		this(source, registerCount, blocks, entry, exceptionRegions, false, List.of(), Map.of());
	}
}
