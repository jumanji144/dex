package me.darknet.dex.tree.type;

public sealed interface ClassType extends Type permits ReferenceType, PrimitiveType {
}
