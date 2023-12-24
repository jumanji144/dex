package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public record ProtoItem(StringItem shortyDescriptor, TypeItem returnType, List<TypeItem> parameters) implements Item {

    public static final ItemCodec<ProtoItem> CODEC = new ItemCodec<>() {
        @Override
        public ProtoItem read0(Input input, DexMapAccess context) throws IOException {
            int shortyDescriptorIndex = input.readInt();
            int returnTypeIndex = input.readInt();
            int parametersOffset = input.readInt();
            if (parametersOffset == 0) {
                return new ProtoItem(context.strings().get(shortyDescriptorIndex), context.types().get(returnTypeIndex),
                        Collections.emptyList());
            }

            List<TypeItem> parameters = TypeListItem.CODEC.read(input.slice(parametersOffset), context).types();
            return new ProtoItem(context.strings().get(shortyDescriptorIndex), context.types().get(returnTypeIndex), parameters);
        }

        @Override
        public void write0(ProtoItem value, Output output, DexMapAccess context) throws IOException {
            // TODO
        }
    };

}
