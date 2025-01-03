package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FormatAAopBBBB(int op, int a, int b) implements Format {
    // AA|op BBBB [AA: 8 bits, op: 8 bits, BBBB: 16 bits], Formats: 20bc (unused), 22x, 21t, 21s, 21h, 21c

    public static final FormatCodec<FormatAAopBBBB> CODEC = new FormatCodec<>() {

        @Override
        public FormatAAopBBBB read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            int b = input.readUnsignedShort();
            return new FormatAAopBBBB(
                    value & 0xFF,
                    (value >> 8) & 0xFF,
                    b
            );
        }

        @Override
        public void write(FormatAAopBBBB value, Output output) throws IOException {
            output.writeShort(
                    (value.a() << 8) |
                            value.op()
            );
            output.writeShort(value.b());
        }
    };

    @Override
    public int size() {
        return 2;
    }
}
