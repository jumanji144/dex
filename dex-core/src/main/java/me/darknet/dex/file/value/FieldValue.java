package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.FieldItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record FieldValue(@NotNull FieldItem item) implements Value {

    public static final ValueCodec<FieldValue> CODEC = new ValueCodec<>() {
        @Override
        public FieldValue read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            return new FieldValue(context.fields().get((int) input.readUnsignedInt()));
        }

        @Override
        public void write(FieldValue value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeInt(context.index().fields().indexOf(value.item));
        }

        @Override
        public int size() {
            return 4;
        }
    };

    @Override
    public int type() {
        return 0x19;
    }
}
