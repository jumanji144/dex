package me.darknet.dex.file.instructions;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Codec;
import me.darknet.dex.io.ContextCodec;

public interface FormatCodec<T extends Format> extends Codec<T> {}