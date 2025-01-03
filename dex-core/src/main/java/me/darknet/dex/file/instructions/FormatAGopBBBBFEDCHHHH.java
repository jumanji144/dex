package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FormatAGopBBBBFEDCHHHH(int op, int a, int b, int c, int d, int e, int f, int g, int h) implements Format {
    // A|G|op BBBB F|E|D|C HHHH [A: 4 bits, G: 4 bits, op: 8 bits, BBBB: 16 bits, F: 4 bits, E: 4 bits, D: 4 bits, C: 4 bits, HHHH: 16 bits]
    // Formats: 45cc

    public static final FormatCodec<FormatAGopBBBBFEDCHHHH> CODEC = new FormatCodec<>() {

        @Override
        public FormatAGopBBBBFEDCHHHH read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            int b = input.readUnsignedShort();
            int fedc = input.readUnsignedShort();
            int hhhh = input.readUnsignedShort();
            int op = value & 0xFF;
            int a = (value >> 12) & 0xF;
            int g = (value >> 8) & 0xF;
            int c = fedc & 0xF;
            int d = (fedc >> 4) & 0xF;
            int e = (fedc >> 8) & 0xF;
            int f = (fedc >> 12) & 0xF;

            return new FormatAGopBBBBFEDCHHHH(
                    op, a, b, c, d, e, f, g, hhhh
            );
        }

        public void write(FormatAGopBBBBFEDCHHHH value, Output output) throws IOException {
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
            output.writeShort(value.h());
        }

    };

    @Override
    public int size() {
        return 4;
    }
}
