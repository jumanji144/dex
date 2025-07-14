package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record MethodHandleItem(int type, int index) implements Item {

    public static final ItemCodec<MethodHandleItem> CODEC = new ItemCodec<>() {

        @Override
        public MethodHandleItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int type = input.readUnsignedShort();
            input.readUnsignedShort(); // unused
            int index = input.readUnsignedShort();
            input.readUnsignedShort(); // unused
            return new MethodHandleItem(type, index);
        }

        @Override
        public void write0(MethodHandleItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeShort(value.type);
            output.writeShort(0); // unused
            output.writeShort(value.index);
            output.writeShort(0); // unused
        }
    };

}
