package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record CharValue(char value) implements Value {

    public static final ValueCodec<CharValue> CODEC = new ValueCodec<>() {

        @Override
        public CharValue read(Input input, DexMapAccess context) throws IOException {
            return new CharValue(input.readChar());
        }

        @Override
        public void write(CharValue value, Output output, WriteContext context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeChar(value.value);
        }

        @Override
        public int size() {
            return 2;
        }
    };

    @Override
    public int type() {
        return 0x03;
    }
}
