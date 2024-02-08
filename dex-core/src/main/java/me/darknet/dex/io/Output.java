package me.darknet.dex.io;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public interface Output extends DataOutput, Seekable {

    void order(ByteOrder order);

    ByteOrder order();

    Output position(int position);

    Output seek(int offset);

    void writeBytes(byte[] bytes) throws IOException;

    void writeBytes(byte[] bytes, int offset, int length) throws IOException;

    void writeLeb128(int value) throws IOException;

    void writeULeb128(int value) throws IOException;

    void writeULeb128p1(int value) throws IOException;

}
