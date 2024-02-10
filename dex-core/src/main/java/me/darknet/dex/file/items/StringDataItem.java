package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record StringDataItem(String string) implements Item {

    public static final ItemCodec<StringDataItem> CODEC = new ItemCodec<>() {
        @Override
        public StringDataItem read0(Input input, DexMapAccess context) throws IOException {
            return new StringDataItem(input.readUTF());
        }

        @Override
        public void write0(StringDataItem value, Output output, WriteContext context) throws IOException {
            output.writeUTF(value.string());
        }
    };

}
