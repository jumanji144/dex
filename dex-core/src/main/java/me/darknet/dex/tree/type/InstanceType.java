package me.darknet.dex.tree.type;

import org.jetbrains.annotations.NotNull;

public final class InstanceType implements ReferenceType {

    private final String internalName;
    private final String externalName;
    private final String descriptor;

    public InstanceType(@NotNull String descriptor) {
        this.descriptor = descriptor;
        this.internalName = descriptor.substring(1, descriptor.length() - 1);
        this.externalName = Types.externalName(internalName);
    }

    @Override
    public @NotNull String internalName() {
        return internalName;
    }

    @Override
    public @NotNull String externalName() {
        return externalName;
    }

    @Override
    public @NotNull String descriptor() {
        return descriptor;
    }

    @Override
    public String toString() {
        return descriptor;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof InstanceType that)) return false;

        return descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return descriptor.hashCode();
    }
}
