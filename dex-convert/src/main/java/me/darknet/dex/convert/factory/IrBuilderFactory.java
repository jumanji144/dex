package me.darknet.dex.convert.factory;

import me.darknet.dex.convert.ir.build.IrBuilder;
import me.darknet.dex.tree.definitions.MethodMember;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating {@link IrBuilder} instances for given methods.
 */
public interface IrBuilderFactory {
	/**
	 * @param method
	 * 		Method with code to convert to IR.
	 *
	 * @return A new IR builder for the given method.
	 */
	@NotNull IrBuilder newBuilder(@NotNull MethodMember method);
}
