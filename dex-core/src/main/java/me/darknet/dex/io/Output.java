package me.darknet.dex.io;

import org.jetbrains.annotations.NotNull;

import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public interface Output extends DataOutput, Seekable {

    void order(@NotNull ByteOrder order);

    @NotNull ByteOrder order();

    @NotNull Output position(int position);

    @NotNull Output seek(int offset);

    /**
     * Creates a new buffer of the same type
     * @return a new buffer
     */
    @NotNull Output newOutput();

    @NotNull ByteBuffer buffer();

    void pipe(@NotNull OutputStream output) throws IOException;

    void write(@NotNull Output output) throws IOException;

    void writeBytes(byte[] bytes) throws IOException;

    void writeBytes(byte[] bytes, int offset, int length) throws IOException;

    void writeLeb128(int value) throws IOException;

    void writeULeb128(int value) throws IOException;

    void writeULeb128p1(int value) throws IOException;

    static @NotNull Output wrap() {
        return new GrowingOutput();
    }
}
