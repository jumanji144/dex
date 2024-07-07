package me.darknet.dex.tree.type;

public final class InstanceType implements ReferenceType {

    private final String internalName;
    private final String externalName;
    private final String descriptor;

    public InstanceType(String descriptor) {
        this.descriptor = descriptor;
        this.internalName = descriptor.substring(1, descriptor.length() - 1);
        this.externalName = Types.externalName(internalName);
    }

    @Override
    public String internalName() {
        return internalName;
    }

    @Override
    public String externalName() {
        return externalName;
    }

    @Override
    public String descriptor() {
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
