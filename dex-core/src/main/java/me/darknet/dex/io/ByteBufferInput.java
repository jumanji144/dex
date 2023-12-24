package me.darknet.dex.io;

import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public record ByteBufferInput(ByteBuffer buffer) implements Input {

    public ByteBufferInput(InputStream stream, ByteOrder order) throws IOException {
        this(ByteBuffer.wrap(stream.readAllBytes()).order(order));
    }

    @Override
    public void order(ByteOrder order) {
        buffer.order(order);
    }

    @Override
    public ByteOrder order() {
        return buffer.order();
    }

    private ByteBuffer check(int length) throws IOException {
        if (buffer.remaining() < length) {
            throw new IOException("Not enough bytes left in buffer");
        }
        return buffer;
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        check(b.length).get(b);
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        check(len).get();
    }

    @Override
    public int skipBytes(int n) throws IOException {
        int skipped = Math.min(n, buffer.remaining());
        buffer.position(buffer.position() + skipped);
        return skipped;
    }

    @Override
    public boolean readBoolean() throws IOException {
        return check(1).get() != 0;
    }

    @Override
    public byte readByte() throws IOException {
        return check(1).get();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return readByte() & 0xFF;
    }

    @Override
    public short readShort() throws IOException {
        return check(2).getShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return readShort() & 0xFFFF;
    }

    @Override
    public char readChar() throws IOException {
        return check(2).getChar();
    }

    @Override
    public int readInt() throws IOException {
        return check(4).getInt();
    }

    @Override
    public long readLong() throws IOException {
        return check(8).getLong();
    }

    @Override
    public float readFloat() throws IOException {
        return check(4).getFloat();
    }

    @Override
    public double readDouble() throws IOException {
        return check(8).getDouble();
    }

    @Override
    public String readLine() throws IOException {
        throw new UnsupportedOperationException("readLine is not supported");
    }

    @Override
    public String readUTF() throws IOException {
        long utf16Size = readULeb128();
        // read until null byte
        NullTerminatedCharsetDecoder decoder = new NullTerminatedCharsetDecoder(StandardCharsets.UTF_8);
        CharBuffer chars = CharBuffer.allocate((int) utf16Size);
        decoder.decode(check((int) utf16Size), chars, true);
        return chars.toString();
    }

    @Override
    public byte[] readBytes(int length) throws IOException {
        byte[] bytes = new byte[length];
        readFully(bytes);
        return bytes;
    }

    @Override
    public long readULeb128() throws IOException {
        long value = 0;
        int next = 0;
        int shift = 0;
        do {
            next = readUnsignedByte();
            value |= (long) (next & 0x7F) << shift;
            shift += 7;
        } while ((next & 0x80) != 0);
        return value;
    }

    @Override
    public long readULeb128p1() throws IOException {
        return readULeb128() - 1;
    }

    @Override
    public long readLeb128() throws IOException {
        long value = 0;
        int next = 0;
        int shift = 0;
        do {
            next = readUnsignedByte();
            value |= (long) (next & 0x7F) << shift;
            shift += 7;
        } while ((next & 0x80) != 0);
        if (((shift < 64) && (next & 0x40) != 0)) {
            value |= -(1L << shift);
        }
        return value;
    }

    @Override
    public long readUnsignedInt() throws IOException {
        return readInt() & 0xFFFFFFFFL;
    }

    @Override
    public int position() {
        return buffer.position();
    }

    @Override
    public Input position(int position) {
        buffer.position(position);
        return this;
    }

    @Override
    public Input seek(int offset) {
        buffer.position(buffer.position() + offset);
        return this;
    }

    @Override
    public Input slice(int offset, int length) {
        return new ByteBufferInput(buffer.duplicate().position(offset).limit(offset + length).order(buffer.order()));
    }

    @Override
    public Input slice(int offset) {
        return new ByteBufferInput(buffer.duplicate().position(offset).order(buffer.order()));
    }

    @Override
    public int size() {
        return buffer.limit();
    }
}
