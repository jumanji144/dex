package me.darknet.dex.tree.type;

public record PrimitiveType(String descriptor, int kind, String name) implements ClassType {

    @Override
    public String toString() {
        return descriptor;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PrimitiveType that)) return false;

        return descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return descriptor.hashCode();
    }
}
