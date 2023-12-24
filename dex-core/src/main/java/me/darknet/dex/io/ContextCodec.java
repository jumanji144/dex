package me.darknet.dex.io;

import java.io.IOException;

public interface ContextCodec<T, C> {

    T read(Input input, C context) throws IOException;

    void write(T value, Output output, C context) throws IOException;

}
