package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Objects;

public record FieldItem(@NotNull TypeItem owner, @NotNull TypeItem type, @NotNull StringItem name) implements Item {

    public static final ItemCodec<FieldItem> CODEC = new ItemCodec<>() {
        @Override
        public FieldItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int ownerIndex = input.readUnsignedShort();
            int typeIndex = input.readUnsignedShort();
            int nameIndex = input.readInt();
            return new FieldItem(context.types().get(ownerIndex), context.types().get(typeIndex),
                    context.strings().get(nameIndex));
        }

        @Override
        public void write0(FieldItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeShort(context.index().types().indexOf(value.owner()));
            output.writeShort(context.index().types().indexOf(value.type()));
            output.writeInt(context.index().strings().indexOf(value.name()));
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(owner, type, name);
    }
}
