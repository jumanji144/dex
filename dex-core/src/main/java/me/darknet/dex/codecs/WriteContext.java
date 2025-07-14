package me.darknet.dex.codecs;

import me.darknet.dex.file.DexMapAccess;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public record WriteContext(@NotNull DexMapAccess index, @NotNull Map<Object, Integer> offsets, int dataOffset) {

    public int offset(Object item) {
        return offsets.get(item) + dataOffset;
    }

}
