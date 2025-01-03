package me.darknet.dex.file;

import java.util.Arrays;
import java.util.Objects;

public record DexHeader(int version, byte[] link, DexMap map) {

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
