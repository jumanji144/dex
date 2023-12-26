package me.darknet.dex.io;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public record ByteBufferOutput(ByteBuffer buffer) implements Output {
    @Override
    public void order(ByteOrder order) {

    }

    @Override
    public ByteOrder order() {
        return null;
    }

    @Override
    public int position() {
        return 0;
    }

    @Override
    public Output position(int position) {
        return null;
    }

    @Override
    public void writeBytes(byte[] bytes) throws IOException {

    }

    @Override
    public void writeBytes(byte[] bytes, int offset, int length) throws IOException {

    }

    @Override
    public Seekable seek(int offset) {
        return null;
    }

    @Override
    public void writeULeb128(long value) throws IOException {

    }

    @Override
    public void writeULeb128p1(long value) throws IOException {

    }

    @Override
    public void writeLeb128(long value) throws IOException {

    }

    @Override
    public void write(int b) throws IOException {

    }

    @Override
    public void write(@NotNull byte[] b) throws IOException {

    }

    @Override
    public void write(@NotNull byte[] b, int off, int len) throws IOException {

    }

    @Override
    public void writeBoolean(boolean v) throws IOException {

    }

    @Override
    public void writeByte(int v) throws IOException {

    }

    @Override
    public void writeShort(int v) throws IOException {

    }

    @Override
    public void writeChar(int v) throws IOException {

    }

    @Override
    public void writeInt(int v) throws IOException {

    }

    @Override
    public void writeLong(long v) throws IOException {

    }

    @Override
    public void writeFloat(float v) throws IOException {

    }

    @Override
    public void writeDouble(double v) throws IOException {

    }

    @Override
    public void writeBytes(@NotNull String s) throws IOException {

    }

    @Override
    public void writeChars(@NotNull String s) throws IOException {

    }

    @Override
    public void writeUTF(@NotNull String s) throws IOException {

    }
}
