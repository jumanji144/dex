package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record MethodItem(TypeItem owner, ProtoItem proto, StringItem name) implements Item {

    public static final ItemCodec<MethodItem> CODEC = new ItemCodec<>() {
        @Override
        public MethodItem read0(Input input, DexMapAccess context) throws IOException {
            int ownerIndex = input.readUnsignedShort();
            int protoIndex = input.readUnsignedShort();
            int nameIndex = input.readInt();
            return new MethodItem(context.types().get(ownerIndex), context.protos().get(protoIndex),
                    context.strings().get(nameIndex));
        }

        @Override
        public void write0(MethodItem value, Output output, DexMapAccess context) throws IOException {
            output.writeShort(context.types().indexOf(value.owner()));
            output.writeShort(context.protos().indexOf(value.proto()));
            output.writeInt(context.strings().indexOf(value.name()));
        }
    };
}
