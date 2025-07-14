package me.darknet.dex.io;

import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public interface Input extends DataInput, Seekable, Slicable {

    void order(@NotNull ByteOrder order);

    @NotNull ByteOrder order();

    @NotNull Input position(int position);

    @NotNull Input seek(int offset);

    @NotNull Input slice(int offset, int length);

    @NotNull Input slice(int offset);

    int peek(int offset) throws IOException;

    int size();

    byte[] readBytes(int length) throws IOException;

    int readULeb128() throws IOException;

    int readULeb128p1() throws IOException;

    int readLeb128() throws IOException;

    long readUnsignedInt() throws IOException;

    static @NotNull Input wrap(byte[] bytes) {
        return wrap(bytes, ByteOrder.BIG_ENDIAN);
    }

    static @NotNull Input wrap(byte[] bytes, ByteOrder order) {
        return new ByteBufferInput(ByteBuffer.wrap(bytes).order(order));
    }

    static @NotNull Input wrap(@NotNull ByteBuffer buffer) {
        return new ByteBufferInput(buffer);
    }

    static @NotNull Input wrap(@NotNull Output output) {
        return new ByteBufferInput(output.buffer());
    }
}
