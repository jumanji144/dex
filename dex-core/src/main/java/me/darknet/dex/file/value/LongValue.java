package me.darknet.dex.file.value;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record LongValue(long value) implements Value {

    public static final ValueCodec<LongValue> CODEC = new ValueCodec<>() {

        @Override
        public LongValue read(Input input, DexMapAccess context) throws IOException {
            return new LongValue(input.readLong());
        }

        @Override
        public void write(LongValue value, Output output, DexMapAccess context) throws IOException {
            output.writeLong(value.value);
        }

        @Override
        public int size() {
            return 8;
        }
    };

    @Override
    public int type() {
        return 0x06;
    }

}
