package me.darknet.dex.io;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

public class GrowingOutput implements Output {

    // copied from jdk/src/java.base/share/classes/jdk/internal/misc/Unsafe.java
    private static final boolean BIG_ENDIAN = true;

    private static char convEndian(boolean big, char n)   { return big == BIG_ENDIAN ? n : Character.reverseBytes(n); }
    private static short convEndian(boolean big, short n) { return big == BIG_ENDIAN ? n : Short.reverseBytes(n)    ; }
    private static int convEndian(boolean big, int n)     { return big == BIG_ENDIAN ? n : Integer.reverseBytes(n)  ; }
    private static long convEndian(boolean big, long n)   { return big == BIG_ENDIAN ? n : Long.reverseBytes(n)     ; }

    private byte[] bytes = new byte[32]; // 32 initial size
    private int position = 0;
    private boolean bigEndian;

    public GrowingOutput() {
        order(ByteOrder.nativeOrder());
    }

    private void ensureCapacity(int size) {
        if (position + size > bytes.length) { // simple growth strategy
            // compute new length
            int length = bytes.length;
            while (position + size > length) {
                length *= 2;
            }
            byte[] newBytes = new byte[length];
            System.arraycopy(bytes, 0, newBytes, 0, bytes.length);
            bytes = newBytes;
        }
    }

    @Override
    public void order(@NotNull ByteOrder order) {
        bigEndian = order == ByteOrder.BIG_ENDIAN;
    }

    @Override
    public @NotNull ByteOrder order() {
        return bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    @Override
    public int position() {
        return position;
    }

    @Override
    public @NotNull Output position(int position) {
        ensureCapacity(position);
        this.position = position;
        return this;
    }

    @Override
    public @NotNull Output seek(int offset) {
        ensureCapacity(position + offset);
        this.position += offset;
        return this;
    }

    @Override
    public @NotNull Output newOutput() {
        GrowingOutput output = new GrowingOutput();
        output.order(order());
        return output;
    }

    public @NotNull ByteBuffer buffer() {
        return ByteBuffer.wrap(bytes, 0, position).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public void write(@NotNull Output output) throws IOException {
        if(output instanceof GrowingOutput go) {
            write(go.bytes, 0, go.position);
            return;
        }
        write(output.buffer());
    }

    private void write(ByteBuffer buffer) {
        // write all bytes from 0 to the current position
        ensureCapacity(buffer.limit());
        buffer.get(bytes, 0, buffer.limit());
        position += buffer.limit();

    }

    @Override
    public void writeBytes(byte[] bytes) throws IOException {
       write(bytes, 0, bytes.length);
    }

    @Override
    public void writeBytes(byte[] bytes, int offset, int length) throws IOException {
        write(bytes, offset, length);
    }

    @Override
    public void writeLeb128(int value) throws IOException {
        ensureCapacity(5); // max size of leb128 is 5 bytes
        byte next;
        do {
            next = (byte) (value & 0x7f);
            value >>>= 7;
            if (value != 0) {
                next |= (byte) 0x80;
            }
            write(next);
        } while (value != 0);
    }

    @Override
    public void writeULeb128(int value) throws IOException {
        writeLeb128(value);
    }

    @Override
    public void writeULeb128p1(int value) throws IOException {
        writeULeb128(value + 1);
    }

    @Override
    public void write(int b) throws IOException {
        ensureCapacity(1);
        bytes[position++] = (byte) b;
    }

    @Override
    public void write(byte @NotNull [] b) throws IOException {
        ensureCapacity(b.length);
        System.arraycopy(b, 0, bytes, position, b.length);
        position += b.length;
    }

    @Override
    public void write(byte @NotNull [] b, int off, int len) throws IOException {
        if(len == 0)
            return;
        ensureCapacity(len);
        System.arraycopy(b, off, bytes, position, len);
        position += len;
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
        write(v ? 1 : 0);
    }

    @Override
    public void writeByte(int v) throws IOException {
        write(v);
    }

    @Override
    public void writeShort(int v) throws IOException {
        ensureCapacity(2);
        int value = convEndian(bigEndian, (short) v);
        bytes[position++] = (byte) (value >>> 8);
        bytes[position++] = (byte) value;
    }

    @Override
    public void writeChar(int v) throws IOException {
        ensureCapacity(2);
        int value = convEndian(bigEndian, (char) v);
        bytes[position++] = (byte) (value >>> 8);
        bytes[position++] = (byte) value;
    }

    @Override
    public void writeInt(int v) throws IOException {
        ensureCapacity(4);
        int value = convEndian(bigEndian, v);
        bytes[position++] = (byte) (value >>> 24);
        bytes[position++] = (byte) (value >>> 16);
        bytes[position++] = (byte) (value >>> 8);
        bytes[position++] = (byte) value;
    }

    @Override
    public void writeLong(long v) throws IOException {
        ensureCapacity(8);
        long value = convEndian(bigEndian, v);
        bytes[position++] = (byte) (value >>> 56);
        bytes[position++] = (byte) (value >>> 48);
        bytes[position++] = (byte) (value >>> 40);
        bytes[position++] = (byte) (value >>> 32);
        bytes[position++] = (byte) (value >>> 24);
        bytes[position++] = (byte) (value >>> 16);
        bytes[position++] = (byte) (value >>> 8);
        bytes[position++] = (byte) value;
    }

    @Override
    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToRawIntBits(v));
    }

    @Override
    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToRawLongBits(v));
    }

    @Override
    public void writeBytes(@NotNull String s) throws IOException {
        write(s.getBytes());
    }

    @Override
    public void writeChars(@NotNull String s) throws IOException {
        ensureCapacity(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            writeChar(s.charAt(i));
        }
    }

    @Override
    public void writeUTF(@NotNull String s) throws IOException {
        // according to the dalvik executable format
        writeULeb128(s.length());
        writeBytes(s);
        write(0);
    }

    @Override
    public void pipe(@NotNull OutputStream output) throws IOException {
        output.write(bytes, 0, position);
    }
}
