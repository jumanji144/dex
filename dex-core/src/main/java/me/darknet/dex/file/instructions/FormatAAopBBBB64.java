package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FormatAAopBBBB64(int op, int a, long b) implements Format {
    // AA|op BBBBlo BBBB BBBB BBBBhi [AA: 8 bits, op: 8 bits, BBBBlo: 32 bits, BBBBhi: 32 bits], Formats: 51l, 52l

    public static final FormatCodec<FormatAAopBBBB64> CODEC = new FormatCodec<>() {

        @Override
        public FormatAAopBBBB64 read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            long b = input.readLong();
            return new FormatAAopBBBB64(
                    value & 0xFF,
                    (value >> 8) & 0xFF,
                    b
            );
        }

        @Override
        public void write(FormatAAopBBBB64 value, Output output) throws IOException {
            output.writeShort(
                    (value.a() << 8) |
                            value.op()
            );
            output.writeLong(value.b());
        }
    };

    @Override
    public int size() {
        return 5;
    }
}
