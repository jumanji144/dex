package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FormatBAopCCCC(int op, int a, int b, int c) implements Format {
    // B|A|op CCCC [B: 4 bits, A: 4 bits, op: 8 bits, CCCC: 16 bits], Formats: 22t, 22s, 22c, 22cs

    public static final FormatCodec<FormatBAopCCCC> CODEC = new FormatCodec<>() {

        @Override
        public FormatBAopCCCC read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            int cc = input.readUnsignedShort();
            return new FormatBAopCCCC(
                    value & 0xFF,
                    (value >> 8) & 0xF,
                    (value >> 12) & 0xF,
                    cc
            );
        }

        @Override
        public void write(FormatBAopCCCC value, Output output) throws IOException {
            output.writeShort(
                    (value.b() << 12) |
                    (value.a() << 8) |
                    value.op()
            );
            output.writeShort(value.c());
        }

    };

    @Override
    public int size() {
        return 2;
    }
}
