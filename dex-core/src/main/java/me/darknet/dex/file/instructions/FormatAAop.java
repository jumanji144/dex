package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record FormatAAop(int op, int a) implements Format {
    // AA|op [AA: 8 bits, op: 8 bits], Formats: 11x, 10t

    public static final FormatCodec<FormatAAop> CODEC = new FormatCodec<>() {

        @Override
        public @NotNull FormatAAop read(@NotNull Input input) throws IOException {
            int value = input.readUnsignedShort();
            return new FormatAAop(
                    value & 0xFF,
                    (value >> 8) & 0xFF
            );
        }

        @Override
        public void write(@NotNull FormatAAop value, @NotNull Output output) throws IOException {
            output.writeShort(
                    (value.a() << 8) |
                    value.op()
            );
        }


    };

}
