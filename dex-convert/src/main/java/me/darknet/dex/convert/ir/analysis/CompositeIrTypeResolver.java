package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.tree.type.ReferenceType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Deterministic resolver that consults sources in order. */
public final class CompositeIrTypeResolver implements IrTypeResolver {
	private final List<IrTypeResolver> resolvers;

	public CompositeIrTypeResolver(@NotNull List<? extends IrTypeResolver> resolvers) {
		this.resolvers = List.copyOf(resolvers);
	}

	public CompositeIrTypeResolver(@NotNull IrTypeResolver... resolvers) {
		this(List.of(resolvers));
	}

	@Override
	public @Nullable IrTypeHierarchyNode describe(@NotNull ReferenceType type) {
		for (IrTypeResolver resolver : resolvers) {
			IrTypeHierarchyNode node = resolver.describe(type);
			if (node != null) return node;
		}
		return null;
	}
}
