package me.darknet.dex.codecs;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.ContextCodec;

public interface DexCodec<T> extends ContextCodec<T, DexMapAccess, WriteContext> {
}
