package me.darknet.dex.codecs;

import me.darknet.dex.file.DexMapAccess;

import java.util.Map;

public record WriteContext(DexMapAccess index, Map<Object, Long> offsets) {
}
