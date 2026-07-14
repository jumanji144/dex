package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Arrays;

/** Resolves types available through a caller-provided Java class loader. */
public final class ReflectionIrTypeResolver implements IrTypeResolver {
	private final ClassLoader loader;

	public ReflectionIrTypeResolver(@Nullable ClassLoader loader) {
		this.loader = loader == null ? ClassLoader.getSystemClassLoader() : loader;
	}

	public ReflectionIrTypeResolver() {
		this(Thread.currentThread().getContextClassLoader());
	}

	@Override
	public @Nullable IrTypeHierarchyNode describe(@NotNull ReferenceType type) {
		if (type instanceof ArrayType) {
			return new IrTypeHierarchyNode(type, null,
					Arrays.asList(Types.instanceType(Cloneable.class), Types.instanceType(Serializable.class)), false);
		}
		if (!(type instanceof InstanceType instance)) return null;
		try {
			Class<?> resolved = Class.forName(instance.externalName(), false, loader);
			Class<?> superclass = resolved.getSuperclass();
			InstanceType superType = superclass == null ? null : Types.instanceType(superclass);
			return new IrTypeHierarchyNode(instance, superType,
					Arrays.stream(resolved.getInterfaces()).map(Types::instanceType).toList(),
					resolved.isInterface());
		} catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
			return null;
		}
	}
}
