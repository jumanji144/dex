package me.darknet.dex.file.value;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record ByteValue(byte value) implements Value {

    public static final ValueCodec<ByteValue> CODEC = new ValueCodec<>() {
        @Override
        public ByteValue read(Input input, DexMapAccess context) throws IOException {
            return new ByteValue(input.readByte());
        }

        @Override
        public void write(ByteValue value, Output output, DexMapAccess context) throws IOException {
            output.writeByte(0); // header
            output.writeByte(value.value);
        }
    };

    @Override
    public int type() {
        return 0x00;
    }
}
