package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record AnnotationOffItem(@NotNull AnnotationItem item) implements Item {

    public static final ItemCodec<AnnotationOffItem> CODEC = new ItemCodec<>() {
        @Override
        public AnnotationOffItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int offset = (int) input.readUnsignedInt();
            return new AnnotationOffItem(AnnotationItem.CODEC.read(input.slice(offset), context));
        }

        @Override
        public void write0(AnnotationOffItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeInt(context.offset(value.item()));
        }
    };

    @Override
    public int hashCode() {
        return item.hashCode();
    }
}
