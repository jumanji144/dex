package me.darknet.dex.file.value;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record TypeValue(TypeItem item) implements Value {

    public static final ValueCodec<TypeValue> CODEC = new ValueCodec<>() {
        @Override
        public TypeValue read(Input input, DexMapAccess context) throws IOException {
            return new TypeValue(context.types().get((int) input.readUnsignedInt()));
        }

        @Override
        public void write(TypeValue value, Output output, DexMapAccess context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeInt(context.types().indexOf(value.item));
        }

        @Override
        public int size() {
            return 4;
        }
    };

    @Override
    public int type() {
        return 0x18;
    }
}
