package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.FieldItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record EnumValue(FieldItem constant) implements Value {

    public static final ValueCodec<EnumValue> CODEC = new ValueCodec<>() {
        @Override
        public EnumValue read(Input input, DexMapAccess context) throws IOException {
            return new EnumValue(context.fields().get((int) input.readUnsignedInt()));
        }

        @Override
        public void write(EnumValue value, Output output, WriteContext context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeInt(context.index().fields().indexOf(value.constant));
        }

        @Override
        public int size() {
            return 4;
        }
    };

    @Override
    public int type() {
        return 0x1b;
    }

}
