package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record MethodItem(@NotNull TypeItem owner, @NotNull ProtoItem proto, @NotNull StringItem name) implements Item {

    public static final ItemCodec<MethodItem> CODEC = new ItemCodec<>() {
        @Override
        public MethodItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int ownerIndex = input.readUnsignedShort();
            int protoIndex = input.readUnsignedShort();
            int nameIndex = input.readInt();
            return new MethodItem(context.types().get(ownerIndex), context.protos().get(protoIndex),
                    context.strings().get(nameIndex));
        }

        @Override
        public void write0(MethodItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeShort(context.index().types().indexOf(value.owner()));
            output.writeShort(context.index().protos().indexOf(value.proto()));
            output.writeInt(context.index().strings().indexOf(value.name()));
        }
    };
}
