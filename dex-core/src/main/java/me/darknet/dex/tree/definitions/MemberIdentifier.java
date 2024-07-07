package me.darknet.dex.tree.definitions;

import me.darknet.dex.tree.type.Type;

public record MemberIdentifier(String name, String descriptor) {

    public MemberIdentifier(String name, Type type) {
        this(name, type.descriptor());
    }

    @Override
    public int hashCode() {
        return name.hashCode() ^ descriptor.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemberIdentifier that)) return false;
        return name.equals(that.name) && descriptor.equals(that.descriptor);
    }
}
