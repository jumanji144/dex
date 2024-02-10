package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record BoolValue(boolean value) implements Value {

    public static final ValueCodec<BoolValue> CODEC = new ValueCodec<>() {

        @Override
        public BoolValue read(Input input, DexMapAccess context) throws IOException {
            int header = input.position(-1).readByte();
            return new BoolValue((header & 0x20) != 0);
        }

        @Override
        public void write(BoolValue value, Output output, WriteContext context) throws IOException {
            output.writeByte((value.value ? 1 : 0) << 5 | value.type());
        }

        @Override
        public int size() {
            return 0;
        }
    };
    
    @Override
    public int type() {
        return 0x1f;
    }
}
