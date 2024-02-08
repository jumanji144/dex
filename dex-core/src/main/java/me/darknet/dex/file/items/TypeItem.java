package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record TypeItem(StringItem descriptor) implements Item {

    public static final ItemCodec<TypeItem> CODEC = new ItemCodec<>() {
        @Override
        public TypeItem read0(Input input, DexMapAccess context) throws IOException {
            int index = (int) input.readUnsignedInt();
            return new TypeItem(context.strings().get(index));
        }

        @Override
        public void write0(TypeItem value, Output output, DexMapAccess context) throws IOException {
            output.writeInt(context.strings().indexOf(value.descriptor()));
        }

        @Override
        public int alignment() {
            return 4;
        }
    };
}
