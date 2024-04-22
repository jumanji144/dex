package me.darknet.dex.file;

import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.file.items.MethodItem;

import java.util.Objects;

public record EncodedMethod(MethodItem method, int access, CodeItem code) implements EncodedMember {

    @Override
    public int hashCode() {
        return Objects.hash(method, access, code);
    }
}
