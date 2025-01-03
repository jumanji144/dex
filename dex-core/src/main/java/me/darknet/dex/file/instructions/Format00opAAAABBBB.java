package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record Format00opAAAABBBB(int op, int a, int b) implements Format {
    // ØØ|op AAAA BBBB [ØØ: 8 bits (all zero), op: 8 bits, AAAA: 16 bits, BBBB: 16 bits], Formats: 32x

    public static final FormatCodec<Format00opAAAABBBB> CODEC = new FormatCodec<>() {

        @Override
        public Format00opAAAABBBB read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            int a = input.readUnsignedShort();
            int b = input.readUnsignedShort();
            return new Format00opAAAABBBB(
                    value & 0xFF,
                    a,
                    b
            );
        }

        @Override
        public void write(Format00opAAAABBBB value, Output output) throws IOException {
            output.writeShort(value.op);
            output.writeShort(value.a);
            output.writeShort(value.b);
        }
    };

    @Override
    public int size() {
        return 3;
    }
}
