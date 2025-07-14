package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record CallSiteItem(@NotNull CallSiteDataItem data) implements Item {

    public static final ItemCodec<CallSiteItem> CODEC = new ItemCodec<>() {
        @Override
        public CallSiteItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int offset = input.readInt();
            return new CallSiteItem(CallSiteDataItem.CODEC.read(input.slice(offset), context));
        }

        @Override
        public void write0(CallSiteItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeInt(context.offset(value.data()));
        }
    };

}
