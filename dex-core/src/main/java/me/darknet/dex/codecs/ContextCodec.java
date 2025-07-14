package me.darknet.dex.codecs;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface ContextCodec<T, CR, CW> {

    T read(@NotNull Input input, @NotNull CR context) throws IOException;

    void write(T value, @NotNull Output output, @NotNull CW context) throws IOException;

}
