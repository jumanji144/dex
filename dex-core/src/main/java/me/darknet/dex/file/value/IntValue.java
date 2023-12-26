package me.darknet.dex.file.value;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record IntValue(int value) implements Value {

    public static final ValueCodec<IntValue> CODEC = new ValueCodec<>() {

        @Override
        public IntValue read(Input input, DexMapAccess context) throws IOException {
            return new IntValue(input.readInt());
        }

        @Override
        public void write(IntValue value, Output output, DexMapAccess context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeInt(value.value);
        }

        @Override
        public int size() {
            return 4;
        }
    };

    @Override
    public int type() {
        return 0x04;
    }
}
