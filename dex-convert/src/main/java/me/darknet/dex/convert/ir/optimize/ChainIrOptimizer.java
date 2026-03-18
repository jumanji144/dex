package me.darknet.dex.convert.ir.optimize;

import me.darknet.dex.convert.ir.IrMethod;
import org.jetbrains.annotations.NotNull;

/**
 * Optimizer that applies multiple optimizations in sequence.
 */
public class ChainIrOptimizer implements IrOptimizer {
	private final IrOptimizer[] optimizers;

	public ChainIrOptimizer(IrOptimizer... optimizers) {
		this.optimizers = optimizers;
	}

	@Override
	public void optimizeProgram(@NotNull IrOptimizationContext context) {
		for (IrOptimizer optimizer : optimizers)
			optimizer.optimizeProgram(context);
	}

	@Override
	public void optimizeMethod(@NotNull IrOptimizationContext context, @NotNull IrMethod method) {
		for (IrOptimizer optimizer : optimizers)
			optimizer.optimizeMethod(context, method);
	}
}
