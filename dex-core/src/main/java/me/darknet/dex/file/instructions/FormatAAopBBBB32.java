package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FormatAAopBBBB32(int op, int a, int b) implements Format {
    // AA|op BBBB_lo BBBB_hi [AA: 8 bits, op: 8 bits, BBBB_lo: 16 bits, BBBB_hi: 16 bits], Formats: 31i, 31t, 31c

    public static final FormatCodec<FormatAAopBBBB32> CODEC = new FormatCodec<>() {

        @Override
        public FormatAAopBBBB32 read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            int b = input.readInt();
            return new FormatAAopBBBB32(
                    value & 0xFF,
                    (value >> 8) & 0xFF,
                    b
            );
        }

        @Override
        public void write(FormatAAopBBBB32 value, Output output) throws IOException {
            output.writeShort(
                    (value.a() << 8) |
                            value.op()
            );
            output.writeInt(value.b());
        }
    };

    @Override
    public int size() {
        return 3;
    }
}
