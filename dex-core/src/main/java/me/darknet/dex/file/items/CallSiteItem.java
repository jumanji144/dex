package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record CallSiteItem(CallSiteDataItem data) implements Item {

    public static final ItemCodec<CallSiteItem> CODEC = new ItemCodec<>() {
        @Override
        public CallSiteItem read0(Input input, DexMapAccess context) throws IOException {
            int offset = input.readInt();
            return new CallSiteItem(CallSiteDataItem.CODEC.read(input.slice(offset), context));
        }

        @Override
        public void write0(CallSiteItem value, Output output, DexMapAccess context) throws IOException {
            // TODO
        }
    };

}
