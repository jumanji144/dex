package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record StringValue(@NotNull StringItem string) implements Value {

    public static final ValueCodec<StringValue> CODEC = new ValueCodec<>() {
        @Override
        public StringValue read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            return new StringValue(context.strings().get((int) input.readUnsignedInt()));
        }

        @Override
        public void write(StringValue value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeInt(context.index().strings().indexOf(value.string));
        }

        @Override
        public int size() {
            return 4;
        }
    };

    @Override
    public int type() {
        return 0x17;
    }
}
