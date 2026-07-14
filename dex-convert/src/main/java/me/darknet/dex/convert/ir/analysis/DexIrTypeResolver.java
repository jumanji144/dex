package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.ReferenceType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolves reference hierarchy facts from the current DEX definitions. */
public final class DexIrTypeResolver implements IrTypeResolver {
	private final Map<String, IrTypeHierarchyNode> nodes;

	public DexIrTypeResolver(@NotNull List<ClassDefinition> definitions) {
		Map<String, IrTypeHierarchyNode> index = new HashMap<>();
		for (ClassDefinition definition : definitions) {
			InstanceType type = definition.getType();
			index.put(type.internalName(), new IrTypeHierarchyNode(type, definition.getSuperClass(),
					definition.getInterfaces(), (definition.getAccess() & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0));
		}
		nodes = Map.copyOf(index);
	}

	@Override
	public @Nullable IrTypeHierarchyNode describe(@NotNull ReferenceType type) {
		return type instanceof InstanceType instance ? nodes.get(instance.internalName()) : null;
	}
}
