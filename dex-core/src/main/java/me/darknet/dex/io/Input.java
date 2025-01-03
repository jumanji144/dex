package me.darknet.dex.io;

import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public interface Input extends DataInput, Seekable, Slicable {

    void order(ByteOrder order);

    ByteOrder order();

    Input position(int position);

    Input seek(int offset);

    Input slice(int offset, int length);

    Input slice(int offset);

    int peek(int offset) throws IOException;

    int size();

    byte[] readBytes(int length) throws IOException;

    int readULeb128() throws IOException;

    int readULeb128p1() throws IOException;

    int readLeb128() throws IOException;

    long readUnsignedInt() throws IOException;

    static Input wrap(byte[] bytes) {
        return wrap(bytes, ByteOrder.BIG_ENDIAN);
    }

    static Input wrap(byte[] bytes, ByteOrder order) {
        return new ByteBufferInput(ByteBuffer.wrap(bytes).order(order));
    }

    static Input wrap(ByteBuffer buffer) {
        return new ByteBufferInput(buffer);
    }

    static Input wrap(Output output) {
        return new ByteBufferInput(output.buffer());
    }

}
