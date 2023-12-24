package me.darknet.dex.io;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteOrder;

public interface Output extends DataOutput, Seekable {

    void order(ByteOrder order);

    ByteOrder order();

    Output position(int position);

    void writeULeb128(long value) throws IOException;

    void writeULeb128p1(long value) throws IOException;

    void writeLeb128(long value) throws IOException;

}
