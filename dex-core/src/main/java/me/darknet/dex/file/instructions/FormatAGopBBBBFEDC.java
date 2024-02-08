package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FormatAGopBBBBFEDC(int op, int a, int b, int c, int d, int e, int f, int g) implements Format {
    // A|G|op BBBB F|E|D|C [A: 4 bits, G: 4 bits, op: 8 bits, BBBB: 16 bits, F: 4 bits, E: 4 bits, D: 4 bits, C: 4 bits]
    // Formats: 35c, 35ms, 35mi

    public static final FormatCodec<FormatAGopBBBBFEDC> CODEC = new FormatCodec<>() {

        @Override
        public FormatAGopBBBBFEDC read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            int b = input.readUnsignedShort();
            int fedc = input.readUnsignedShort();
            int op = value & 0xFF;
            int a = (value >> 12) & 0xF;
            int g = (value >> 8) & 0xF;
            int c = fedc & 0xF;
            int d = (fedc >> 4) & 0xF;
            int e = (fedc >> 8) & 0xF;
            int f = (fedc >> 12) & 0xF;

            return new FormatAGopBBBBFEDC(
                    op, a, b, c, d, e, f, g
            );
        }

        public void write(FormatAGopBBBBFEDC value, Output output) throws IOException {
            output.writeShort(
                    (value.a() << 12) |
                    (value.g() << 8) |
                    value.op()
            );
            output.writeShort(value.b());
            output.writeShort(
                    (value.f() << 12) |
                    (value.e() << 8) |
                    (value.d() << 4) |
                    value.c()
            );
        }

    };

}
