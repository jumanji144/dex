package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record FormatAAopBBBB(int op, int a, int b) implements Format {
    // AA|op BBBB [AA: 8 bits, op: 8 bits, BBBB: 16 bits], Formats: 20bc (unused), 22x, 21t, 21s, 21h, 21c

    public static final FormatCodec<FormatAAopBBBB> CODEC = new FormatCodec<>() {

        @Override
        public @NotNull FormatAAopBBBB read(@NotNull Input input) throws IOException {
            int value = input.readUnsignedShort();
            int b = input.readShort();
            return new FormatAAopBBBB(
                    value & 0xFF,
                    (value >> 8) & 0xFF,
                    b
            );
        }

        @Override
        public void write(@NotNull FormatAAopBBBB value, @NotNull Output output) throws IOException {
            output.writeShort(
                    (value.a() << 8) |
                            value.op()
            );
            output.writeShort(value.b());
        }
    };

    public int ub() {
        // Map s2 to u2
        return b & 0xFFFF;
    }

    @Override
    public int size() {
        return 2;
    }
}
