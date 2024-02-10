package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record NullValue() implements Value {

    public static final NullValue INSTANCE = new NullValue();

    public static final ValueCodec<NullValue> CODEC = new ValueCodec<>() {

        @Override
        public NullValue read(Input input, DexMapAccess context) throws IOException {
            return INSTANCE;
        }

        @Override
        public void write(NullValue value, Output output, WriteContext context) throws IOException {
            output.writeByte(value.type()); // 0 << 5 | 0x1c
        }
    };

    @Override
    public int type() {
        return 0x1c;
    }
}
