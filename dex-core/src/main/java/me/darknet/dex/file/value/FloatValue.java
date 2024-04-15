package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FloatValue(float value) implements Value {

    public static final ValueCodec<FloatValue> CODEC = new ValueCodec<>() {

        @Override
        public FloatValue read(Input input, DexMapAccess context) throws IOException {
            return new FloatValue(input.readFloat());
        }

        @Override
        public void write(FloatValue value, Output output, WriteContext context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeFloat(value.value);
        }

        @Override
        public int size() {
            return 4;
        }
    };

    @Override
    public int type() {
        return 0x10;
    }
}
