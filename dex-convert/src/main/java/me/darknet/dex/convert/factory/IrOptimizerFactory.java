package me.darknet.dex.convert.factory;

import me.darknet.dex.convert.ir.optimize.IrOptimizer;
import me.darknet.dex.convert.ir.optimize.IrOptimizationContext;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating session-scoped {@link IrOptimizer} instances.
 */
public interface IrOptimizerFactory {
	/**
	 * @param context
	 * 		Optimization context for the current conversion session.
	 *
	 * @return A new optimizer instance.
	 */
	@NotNull IrOptimizer newOptimizer(@NotNull IrOptimizationContext context);
}
