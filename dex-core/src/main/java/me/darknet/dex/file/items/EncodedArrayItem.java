package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.value.Value;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record EncodedArrayItem(@NotNull List<Value> values) implements Item {

    public static final ItemCodec<EncodedArrayItem> CODEC = new ItemCodec<>() {
        @Override
        public EncodedArrayItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int size = input.readULeb128();
            List<Value> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                values.add(Value.CODEC.read(input, context));
            }
            return new EncodedArrayItem(values);
        }

        @Override
        public void write0(EncodedArrayItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeULeb128(value.values.size());
            for (Value entry : value.values()) {
                Value.CODEC.write(entry, output, context);
            }
        }
    };

    @Override
    public int hashCode() {
        return Objects.hashCode(values);
    }
}
