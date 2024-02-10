package me.darknet.dex.io;

import java.io.IOException;

public interface ContextCodec<T, CR, CW> {

    default T read(Input input) throws IOException {
        return read(input, null);
    }

    default void write(T value, Output output) throws IOException {
        write(value, output, null);
    }

    default T read(Input input, CR context) throws IOException {
        // INFO default behaviour is causes a self reference loop, this would be classified as undefined behaviour,
        // as any codec should override any pair of these methods.
        // this is left unchecked due to lack of compile time checks for this, and a runtime check would be too expensive.
        // so the user is responsible for ensuring that this does not happen.
        return read(input);
    }

    default void write(T value, Output output, CW context) throws IOException {
        write(value, output);
    }

}
