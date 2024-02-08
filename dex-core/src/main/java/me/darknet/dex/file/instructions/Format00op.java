package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record Format00op(int op) implements Format {
    // ØØ|op [ØØ: 8 bits (all zero), op: 8 bits], Formats: 10x

    public static final FormatCodec<Format00op> CODEC = new FormatCodec<>() {

        @Override
        public Format00op read(Input input) throws IOException {
            int value = input.readUnsignedShort();
            return new Format00op(
                    value & 0xFF
            );
        }

        @Override
        public void write(Format00op value, Output output) throws IOException {
            output.writeShort(value.op);
        }
    };

}
