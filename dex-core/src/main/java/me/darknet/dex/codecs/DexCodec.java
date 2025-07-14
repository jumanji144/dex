package me.darknet.dex.codecs;

import me.darknet.dex.file.DexMapAccess;

public interface DexCodec<T> extends ContextCodec<T, DexMapAccess, WriteContext> {
}
