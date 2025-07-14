package me.darknet.dex.file;

import me.darknet.dex.codecs.DexHeaderCodec;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;

public record DexHeader(int version, byte[] link, @NotNull DexMap map) {

    public static final DexHeaderCodec CODEC = new DexHeaderCodec();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DexHeader dexHeader = (DexHeader) o;
        return Objects.equals(map, dexHeader.map) && Objects.deepEquals(link, dexHeader.link)
                && version == dexHeader.version;
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, Arrays.hashCode(link), map);
    }

    @Override
    public String toString() {
        return "DexHeader[]";
    }
}
