package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FormatAAopBBBBCCCC(int op, int a, int b, int c) implements Format {
    // AA|op BBBB CCCC [AA: 8 bits, op: 8 bits, BBBB: 16 bits, CCCC: 16 bits], Formats: 3rc, 3rms, 3rmi

    public static final FormatCodec<FormatAAopBBBBCCCC> CODEC = new FormatCodec<>() {

        @Override
        public FormatAAopBBBBCCCC read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            int b = input.readUnsignedShort();
            int c = input.readUnsignedShort();
            return new FormatAAopBBBBCCCC(
                    value & 0xFF,
                    (value >> 8) & 0xFF,
                    b,
                    c
            );
        }

        @Override
        public void write(FormatAAopBBBBCCCC value, Output output) throws IOException {
            output.writeShort(
                    (value.a() << 8) |
                            value.op()
            );
            output.writeShort(value.b());
            output.writeShort(value.c());
        }
    };

    @Override
    public int size() {
        return 3;
    }
}
