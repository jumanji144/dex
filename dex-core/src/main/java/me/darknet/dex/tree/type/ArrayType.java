package me.darknet.dex.tree.type;

import org.jetbrains.annotations.NotNull;

public final class ArrayType implements ReferenceType {

    private final int dimensions;
    private final ClassType componentType;
    private final String descriptor;

    public ArrayType(@NotNull ClassType componentType) {
        this.dimensions = nextDimension(componentType);
        this.componentType = componentType;

        this.descriptor = "[".repeat(Math.max(0, dimensions)) +
                componentType.descriptor();
    }

    @Override
    public @NotNull String internalName() {
        return descriptor;
    }

    @Override
    public @NotNull String descriptor() {
        return descriptor;
    }

    public int dimensions() {
        return dimensions;
    }

    public @NotNull ClassType componentType() {
        return componentType;
    }

    private static int nextDimension(Type type) {
        return type instanceof ArrayType arrayType ? arrayType.dimensions() + 1 : 1;
    }

    @Override
    public String toString() {
        return descriptor;
    }
}
