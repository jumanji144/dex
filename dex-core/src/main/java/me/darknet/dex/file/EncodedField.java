package me.darknet.dex.file;

import me.darknet.dex.file.items.FieldItem;

import java.util.Objects;

public record EncodedField(FieldItem field, int access) {

    @Override
    public int hashCode() {
        return Objects.hash(field, access);
    }

}
