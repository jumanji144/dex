package me.darknet.dex.tree.definitions;

import me.darknet.dex.tree.type.Type;
import org.jetbrains.annotations.NotNull;

public record MemberIdentifier(@NotNull String name, @NotNull String descriptor) {

    public MemberIdentifier(@NotNull String name, @NotNull Type type) {
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
