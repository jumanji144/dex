package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record TypeListItem(List<TypeItem> types) implements Item {

    public static TypeListItem EMPTY = new TypeListItem(List.of());

    public static final ItemCodec<TypeListItem> CODEC = new ItemCodec<>() {

        @Override
        public int alignment() {
            return 4;
        }

        @Override
        public TypeListItem read0(Input input, DexMapAccess context) throws IOException {
            int size = input.readInt();
            List<TypeItem> types = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                types.add(context.types().get(input.readUnsignedShort()));
            }
            return new TypeListItem(types);
        }

        @Override
        public void write0(TypeListItem value, Output output, WriteContext context) throws IOException {
            output.writeInt(value.types.size());
            for (TypeItem type : value.types) {
                output.writeShort(context.index().types().indexOf(type));
            }
        }
    };

}
