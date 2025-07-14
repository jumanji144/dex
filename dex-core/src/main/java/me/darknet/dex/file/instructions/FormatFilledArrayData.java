package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public record FormatFilledArrayData(int width, byte[] data) implements Format {

    @Override
    public int op() {
        return 0x0300;
    }

    public static final FormatCodec<FormatFilledArrayData> CODEC = new FormatCodec<>() {
        @Override
        public @NotNull FormatFilledArrayData read(@NotNull Input input) throws IOException {
            input.readUnsignedShort(); // discard
            int width = input.readUnsignedShort();
            int size = input.readInt();
            byte[] data = new byte[size * width];
            input.readFully(data);
            if((input.position() & 1) != 0) {
                input.readByte(); // padding
            }
            return new FormatFilledArrayData(width, data);
        }

        @Override
        public void write(@NotNull FormatFilledArrayData value, @NotNull Output output) throws IOException {
            int size = value.data().length / value.width();
            output.writeShort(value.op());
            output.writeShort(value.width());
            output.writeInt(size);
            output.write(value.data());
            if((output.position() & 1) != 0) {
                output.writeByte((byte) 0); // padding
            }
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FormatFilledArrayData that = (FormatFilledArrayData) o;
        return width == that.width && Objects.deepEquals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, Arrays.hashCode(data));
    }

    @Override
    public int size() {
        return 1 + 1 + 2 + (data.length / 2) + (data.length & 1);
    }
}
