package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.value.MethodHandleValue;
import me.darknet.dex.file.value.MethodTypeValue;
import me.darknet.dex.file.value.StringValue;
import me.darknet.dex.file.value.Value;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record CallSiteDataItem(@NotNull MethodHandleValue handle, @NotNull StringValue name, @NotNull MethodTypeValue type,
                              @NotNull List<Value> arguments) implements Item {

    public static final ItemCodec<CallSiteDataItem> CODEC = new ItemCodec<>() {
        @Override
        public CallSiteDataItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int size = input.readULeb128();
            List<Value> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                values.add(Value.CODEC.read(input, context));
            }
            return new CallSiteDataItem(
                    ((MethodHandleValue) values.get(0)),
                    ((StringValue) values.get(1)),
                    ((MethodTypeValue) values.get(2)),
                    values.subList(3, values.size())
            );
        }

        @Override
        public void write0(CallSiteDataItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeULeb128(value.arguments.size() + 3);
            Value.CODEC.write(value.handle, output, context);
            Value.CODEC.write(value.name, output, context);
            Value.CODEC.write(value.type, output, context);
            for (Value v : value.arguments) {
                Value.CODEC.write(v, output, context);
            }
        }
    };

}
