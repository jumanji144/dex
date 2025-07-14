package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public record ProtoItem(@NotNull StringItem shortyDescriptor, @NotNull TypeItem returnType, @NotNull TypeListItem parameters) implements Item {

    public static final ItemCodec<ProtoItem> CODEC = new ItemCodec<>() {
        @Override
        public ProtoItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int shortyDescriptorIndex = input.readInt();
            int returnTypeIndex = input.readInt();
            int parametersOffset = input.readInt();
            if (parametersOffset == 0) {
                return new ProtoItem(context.strings().get(shortyDescriptorIndex), context.types().get(returnTypeIndex),
                        TypeListItem.EMPTY);
            }

            TypeListItem parameters = TypeListItem.CODEC.read(input.slice(parametersOffset), context);
            return new ProtoItem(context.strings().get(shortyDescriptorIndex), context.types().get(returnTypeIndex), parameters);
        }

        @Override
        public void write0(ProtoItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeInt(context.index().strings().indexOf(value.shortyDescriptor()));
            output.writeInt(context.index().types().indexOf(value.returnType()));
            if (value.parameters().types().isEmpty()) {
                output.writeInt(0);
                return;
            }

            output.writeInt(context.offset(value.parameters()));
        }

        @Override
        public int alignment() {
            return 4;
        }
    };

}
