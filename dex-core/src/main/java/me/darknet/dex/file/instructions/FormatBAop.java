package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FormatBAop(int op, int a, int b) implements Format {
    // B|A|op [B: 4 bits, A: 4 bits, op: 8 bits], Formats: 12x, 11n

    public static final FormatCodec<FormatBAop> CODEC = new FormatCodec<>() {

        @Override
        public FormatBAop read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            return new FormatBAop(
                    value & 0xFF,
                    (value >> 8) & 0xF,
                    (value >> 12) & 0xF
            );
        }

        @Override
        public void write(FormatBAop value, Output output) throws IOException {
            output.writeShort(
                    (value.b() << 12) |
                    (value.a() << 8) |
                    value.op()
            );
        }

    };
}
