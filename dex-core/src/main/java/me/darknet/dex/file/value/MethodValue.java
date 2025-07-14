package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.MethodItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record MethodValue(@NotNull MethodItem method) implements Value {

    public static final ValueCodec<MethodValue> CODEC = new ValueCodec<>() {
        @Override
        public MethodValue read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            return new MethodValue(context.methods().get(input.readInt()));
        }

        @Override
        public void write(MethodValue value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeInt(context.index().methods().indexOf(value.method));
        }

        @Override
        public int size() {
            return 4;
        }
    };

    @Override
    public int type() {
        return 0x1a;
    }
}
