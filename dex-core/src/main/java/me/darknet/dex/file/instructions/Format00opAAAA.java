package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record Format00opAAAA(int op, int a) implements Format {
    // ØØ|op AAAA [ØØ: 8 bits (all zero), op: 8 bits, AAAA: 16 bits], Formats: 20t

    public static final FormatCodec<Format00opAAAA> CODEC = new FormatCodec<>() {

        @Override
        public Format00opAAAA read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            int a = input.readUnsignedShort();
            return new Format00opAAAA(
                    value & 0xFF, a
            );
        }

        @Override
        public void write(Format00opAAAA value, Output output) throws IOException {
            output.writeShort(value.op);
            output.writeShort(value.a);
        }


    };

}
