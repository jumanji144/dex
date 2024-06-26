package me.darknet.dex.io;

import me.darknet.dex.builder.Builder;

import java.io.IOException;

/**
 * Codec to serialize and deserialize values.
 * @param <T> the type of the value
 */
public interface Codec<T> {

    T read(Input input) throws IOException;

    void write(T value, Output output) throws IOException;

}
