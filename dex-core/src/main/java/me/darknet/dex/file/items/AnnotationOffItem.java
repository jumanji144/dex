package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record AnnotationOffItem(AnnotationItem item) implements Item {

    public static final ItemCodec<AnnotationOffItem> CODEC = new ItemCodec<>() {
        @Override
        public AnnotationOffItem read0(Input input, DexMapAccess context) throws IOException {
            int offset = (int) input.readUnsignedInt();
            return new AnnotationOffItem(AnnotationItem.CODEC.read(input.slice(offset), context));
        }

        @Override
        public void write0(AnnotationOffItem value, Output output, DexMapAccess context) throws IOException {
            // TODO
        }
    };

}
