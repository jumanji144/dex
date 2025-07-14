package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record FormatAAopBBBBCCCCHHHH(int op, int a, int b, int c, int h) implements Format {
    // AA|op BBBB CCCC HHHH [AA: 8 bits, op: 8 bits, BBBB: 16 bits, CCCC: 16 bits, HHHH: 16 bits], Formats: 4rcc

    public static final FormatCodec<FormatAAopBBBBCCCCHHHH> CODEC = new FormatCodec<>() {

        @Override
        public @NotNull FormatAAopBBBBCCCCHHHH read(@NotNull Input input) throws IOException {
            int value = input.readUnsignedShort();
            int b = input.readUnsignedShort();
            int c = input.readUnsignedShort();
            int h = input.readUnsignedShort();
            return new FormatAAopBBBBCCCCHHHH(
                    value & 0xFF,
                    (value >> 8) & 0xFF,
                    b,
                    c,
                    h
            );
        }

        @Override
        public void write(@NotNull FormatAAopBBBBCCCCHHHH value, @NotNull Output output) throws IOException {
            output.writeShort(
                    (value.a() << 8) |
                            value.op()
            );
            output.writeShort(value.b());
            output.writeShort(value.c());
            output.writeShort(value.h());
        }
    };

    @Override
    public int size() {
        return 4;
    }
}
