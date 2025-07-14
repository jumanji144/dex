package me.darknet.dex.file;

import me.darknet.dex.file.items.FieldItem;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record EncodedField(@NotNull FieldItem field, int access) implements EncodedMember {

    @Override
    public int hashCode() {
        return Objects.hash(field, access);
    }

}
