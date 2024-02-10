package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record AnnotationSetItem(List<AnnotationOffItem> entries) implements Item {

    public static final ItemCodec<AnnotationSetItem> CODEC = new ItemCodec<>() {
        @Override
        public AnnotationSetItem read0(Input input, DexMapAccess context) throws IOException {
            int size = (int) input.readUnsignedInt();
            List<AnnotationOffItem> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                entries.add(AnnotationOffItem.CODEC.read(input, context));
            }
            return new AnnotationSetItem(entries);
        }

        @Override
        public void write0(AnnotationSetItem value, Output output, WriteContext context) throws IOException {
            output.writeInt(value.entries().size());
            for (AnnotationOffItem entry : value.entries()) {
                AnnotationOffItem.CODEC.write(entry, output, context);
            }
        }

        @Override
        public int alignment() {
            return 4;
        }
    };

}
