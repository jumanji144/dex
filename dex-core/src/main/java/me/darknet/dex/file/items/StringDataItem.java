package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Objects;

public record StringDataItem(@NotNull String string) implements Item {

    public static final ItemCodec<StringDataItem> CODEC = new ItemCodec<>() {
        @Override
        public StringDataItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            return new StringDataItem(input.readUTF());
        }

        @Override
        public void write0(StringDataItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeUTF(value.string());
        }
    };

    @Override
    public int hashCode() {
        return string.hashCode();
    }
}
