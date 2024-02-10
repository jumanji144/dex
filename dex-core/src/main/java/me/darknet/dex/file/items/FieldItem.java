package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FieldItem(TypeItem owner, TypeItem type, StringItem name) implements Item {

    public static final ItemCodec<FieldItem> CODEC = new ItemCodec<>() {
        @Override
        public FieldItem read0(Input input, DexMapAccess context) throws IOException {
            int ownerIndex = input.readUnsignedShort();
            int typeIndex = input.readUnsignedShort();
            int nameIndex = input.readInt();
            return new FieldItem(context.types().get(ownerIndex), context.types().get(typeIndex),
                    context.strings().get(nameIndex));
        }

        @Override
        public void write0(FieldItem value, Output output, WriteContext context) throws IOException {
            output.writeShort(context.index().types().indexOf(value.owner()));
            output.writeShort(context.index().types().indexOf(value.type()));
            output.writeInt(context.index().strings().indexOf(value.name()));
        }
    };

}
