package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record Format00opAAAA32(int op, int a) implements Format {
    // ØØ|op AAAA AAAA [ØØ: 8 bits (all zero), op: 8 bits, AAAA AAAA: 32 bits], Formats: 30t

    public static final FormatCodec<Format00opAAAA32> CODEC = new FormatCodec<>() {

        @Override
        public @NotNull Format00opAAAA32 read(@NotNull Input input) throws IOException {
            int value = input.readUnsignedShort();
            int a = input.readInt();
            return new Format00opAAAA32(
                    value & 0xFF, a
            );
        }

        @Override
        public void write(@NotNull Format00opAAAA32 value, @NotNull Output output) throws IOException {
            output.writeShort(value.op);
            output.writeInt(value.a);
        }

    };

    @Override
    public int size() {
        return 3;
    }
}
