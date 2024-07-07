package me.darknet.dex.tree.codec;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;

public interface TreeCodec<D, I> extends ContextMappingCodec<I, D, DexMap, DexMapBuilder> {
}
