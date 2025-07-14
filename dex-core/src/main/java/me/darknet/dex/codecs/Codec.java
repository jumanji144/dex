package me.darknet.dex.codecs;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Codec to serialize and deserialize values.
 * @param <T> the type of the value
 */
public interface Codec<T> {

    @NotNull T read(@NotNull Input input) throws IOException;

    void write(@NotNull T value, @NotNull Output output) throws IOException;

}
