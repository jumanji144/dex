package me.darknet.dex.codecs;

import me.darknet.dex.collections.Multimap;
import me.darknet.dex.file.DexMapAccess;

import java.util.Map;

public record WriteContext(DexMapAccess index, Map<Object, Integer> offsets, int dataOffset) {

    public int offset(Object item) {
        return offsets.get(item) + dataOffset;
    }

}
