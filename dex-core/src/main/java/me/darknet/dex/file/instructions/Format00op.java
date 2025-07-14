package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record Format00op(int op) implements Format {
    // ØØ|op [ØØ: 8 bits (all zero), op: 8 bits], Formats: 10x

    public static final FormatCodec<Format00op> CODEC = new FormatCodec<>() {

        @Override
        public @NotNull Format00op read(@NotNull Input input) throws IOException {
            int value = input.readUnsignedShort();
            return new Format00op(
                    value & 0xFF
            );
        }

        @Override
        public void write(@NotNull Format00op value, @NotNull Output output) throws IOException {
            output.writeShort(value.op);
        }
    };

}
