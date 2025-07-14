package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record StringItem(@NotNull StringDataItem item) implements Item {

    public static final ItemCodec<StringItem> CODEC = new ItemCodec<>() {
        @Override
        public StringItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int offset = (int) input.readUnsignedInt();
            return new StringItem(StringDataItem.CODEC.read(input.slice(offset), context));
        }

        @Override
        public void write0(StringItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeInt(context.offset(value.item()));
        }

        @Override
        public int alignment() {
            return 4;
        }
    };

    public String string() {
        return item.string();
    }

    @Override
    public int hashCode() {
        return 31 * item.hashCode();
    }
}
