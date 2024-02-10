package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record StringValue(StringItem string) implements Value {

    public static final ValueCodec<StringValue> CODEC = new ValueCodec<>() {
        @Override
        public StringValue read(Input input, DexMapAccess context) throws IOException {
            return new StringValue(context.strings().get((int) input.readUnsignedInt()));
        }

        @Override
        public void write(StringValue value, Output output, WriteContext context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeInt(context.index().strings().indexOf(value.string));
        }

        @Override
        public int size() {
            return 4;
        }
    };

    @Override
    public int type() {
        return 0x17;
    }
}
