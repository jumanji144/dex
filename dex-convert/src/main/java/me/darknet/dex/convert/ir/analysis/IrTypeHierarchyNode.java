package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.ReferenceType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Immutable description of one reference type in a resolver's hierarchy. */
public record IrTypeHierarchyNode(
		@NotNull ReferenceType type,
		@Nullable InstanceType superType,
		@NotNull List<InstanceType> interfaces,
		boolean interfaceType) {
	public IrTypeHierarchyNode {
		interfaces = List.copyOf(interfaces);
	}
}
