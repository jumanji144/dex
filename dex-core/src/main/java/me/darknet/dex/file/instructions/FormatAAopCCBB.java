package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record FormatAAopCCBB(int op, int a, int b, int c) implements Format {
    // AA|op CC|BB [AA: 8 bits, op: 8 bits, CC: 8 bits, BB: 8 bits], Formats: 23x, 22b

    public static final FormatCodec<FormatAAopCCBB> CODEC = new FormatCodec<>() {

        @Override
        public @NotNull FormatAAopCCBB read(@NotNull Input input) throws IOException {
            int value = input.readUnsignedShort();
            int cb = input.readUnsignedShort();
            return new FormatAAopCCBB(
                    value & 0xFF,
                    (value >> 8) & 0xFF,
                    cb & 0xFF,
                    (cb >> 8) & 0xFF
            );
        }

        @Override
        public void write(@NotNull FormatAAopCCBB value, @NotNull Output output) throws IOException {
            output.writeShort(
                    (value.a() << 8) |
                            value.op()
            );
            output.writeShort(
                    (value.c() << 8) |
                            value.b()
            );
        }
    };

    @Override
    public int size() {
        return 2;
    }
}
