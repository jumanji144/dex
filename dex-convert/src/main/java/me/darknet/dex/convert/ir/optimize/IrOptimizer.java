package me.darknet.dex.convert.ir.optimize;

import me.darknet.dex.convert.DexConversion;
import me.darknet.dex.convert.ir.IrMethod;
import org.jetbrains.annotations.NotNull;

/**
 * Outline for IR optimization.
 * <ul>
 *     <li>Program-scoped optimizations run first in {@link DexConversion} methods/li>
 *     <li>Method-scoped optimizations run later just before the IR gets lowered</li>
 * </ul>
 */
public interface IrOptimizer {
	default void optimizeProgram(@NotNull IrOptimizationContext context) {
		// no-op
	}

	default void optimizeMethod(@NotNull IrOptimizationContext context, @NotNull IrMethod method) {
		// no-op
	}
}
