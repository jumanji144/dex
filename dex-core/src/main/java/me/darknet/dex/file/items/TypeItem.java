package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record TypeItem(@NotNull StringItem descriptor) implements Item {

    public static final ItemCodec<TypeItem> CODEC = new ItemCodec<>() {
        @Override
        public TypeItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int index = (int) input.readUnsignedInt();
            return new TypeItem(context.strings().get(index));
        }

        @Override
        public void write0(TypeItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeInt(context.index().strings().indexOf(value.descriptor()));
        }

        @Override
        public int alignment() {
            return 4;
        }
    };

    @Override
    public int hashCode() {
        return 31 * descriptor.hashCode();
    }
}
