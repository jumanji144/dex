package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record AnnotationSetRefList(@NotNull List<@Nullable AnnotationSetItem> items) implements Item {

    public static final ItemCodec<AnnotationSetRefList> CODEC = new ItemCodec<>() {
        @Override
        public AnnotationSetRefList read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
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
        public void write0(AnnotationSetRefList value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeInt(value.items().size());
            for (AnnotationSetItem item : value.items()) {
                if (item == null)
                    output.writeInt(0);
                else
                    output.writeInt(context.offset(item));
            }
        }

        @Override
        public int alignment() {
            return 4;
        }
    };

}
