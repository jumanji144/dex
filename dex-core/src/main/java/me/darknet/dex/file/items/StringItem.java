package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record StringItem(StringDataItem item) implements Item {

    public static final ItemCodec<StringItem> CODEC = new ItemCodec<>() {
        @Override
        public StringItem read0(Input input, DexMapAccess context) throws IOException {
            int offset = (int) input.readUnsignedInt();
            return new StringItem(StringDataItem.CODEC.read(input.slice(offset), context));
        }

        @Override
        public void write0(StringItem value, Output output, DexMapAccess context) throws IOException {
            // TODO
        }
    };

}
