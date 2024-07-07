package me.darknet.dex.tree.type;

import java.util.List;

public final class MethodType implements Type {

    private final ClassType returnType;
    private final List<ClassType> parameterTypes;
    private final String descriptor;

    MethodType(ClassType returnType, List<ClassType> parameterTypes) {
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;

        StringBuilder builder = new StringBuilder();
        builder.append('(');
        for (ClassType type : parameterTypes) {
            builder.append(type.descriptor());
        }
        this.descriptor = builder.append(')').append(returnType.descriptor()).toString();
    }

    public ClassType returnType() {
        return returnType;
    }

    public List<ClassType> parameterTypes() {
        return parameterTypes;
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
        if (!(obj instanceof MethodType that)) return false;

        return descriptor.equals(that.descriptor);
    }

    @Override
    public int hashCode() {
        return descriptor.hashCode();
    }
}
