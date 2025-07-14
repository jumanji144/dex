package me.darknet.dex.file;

import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.file.items.MethodItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record EncodedMethod(@NotNull MethodItem method, int access, @Nullable CodeItem code) implements EncodedMember {

    @Override
    public int hashCode() {
        return Objects.hash(method, access, code);
    }
}
