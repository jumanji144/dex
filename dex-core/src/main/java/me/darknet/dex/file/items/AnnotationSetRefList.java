package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record AnnotationSetRefList(List<@Nullable AnnotationSetItem> items) implements Item {

    public static final ItemCodec<AnnotationSetRefList> CODEC = new ItemCodec<>() {
        @Override
        public AnnotationSetRefList read0(Input input, DexMapAccess context) throws IOException {
            int size = (int) input.readUnsignedInt();
            List<AnnotationSetItem> items = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int offset = (int) input.readUnsignedInt();
                if (offset == 0)
                    items.add(null);
                else
                    items.add(AnnotationSetItem.CODEC.read(input.slice(offset), context));
            }
            return new AnnotationSetRefList(items);
        }

        @Override
        public void write0(AnnotationSetRefList value, Output output, DexMapAccess context) throws IOException {

        }

        @Override
        public int alignment() {
            return 4;
        }
    };

}
