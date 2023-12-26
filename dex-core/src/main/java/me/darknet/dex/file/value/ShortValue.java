package me.darknet.dex.file.value;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record ShortValue(short value) implements Value {

    public static final ValueCodec<ShortValue> CODEC = new ValueCodec<>() {

        @Override
        public ShortValue read(Input input, DexMapAccess context) throws IOException {
            return new ShortValue(input.readShort());
        }

        @Override
        public void write(ShortValue value, Output output, DexMapAccess context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeShort(value.value);
        }

        @Override
        public int size() {
            return 2;
        }
    };

    @Override
    public int type() {
        return 0x02;
    }
}
