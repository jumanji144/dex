package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.tree.type.ReferenceType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Supplies reference hierarchy facts to the IR type lattice. */
@FunctionalInterface
public interface IrTypeResolver {
	IrTypeResolver EMPTY = type -> null;

	/** Returns a proven hierarchy node, or {@code null} when the type is unavailable. */
	@Nullable IrTypeHierarchyNode describe(@NotNull ReferenceType type);
}
