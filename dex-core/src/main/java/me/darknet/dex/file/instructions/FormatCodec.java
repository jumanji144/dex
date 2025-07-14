package me.darknet.dex.file.instructions;

import me.darknet.dex.codecs.Codec;

public interface FormatCodec<T extends Format> extends Codec<T> {}