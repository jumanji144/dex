package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.ProtoItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record MethodTypeValue(@NotNull ProtoItem protoItem) implements Value {

    public static final ValueCodec<MethodTypeValue> CODEC = new ValueCodec<>() {
        @Override
        public MethodTypeValue read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            return new MethodTypeValue(context.protos().get((int) input.readUnsignedInt()));
        }

        @Override
        public void write(MethodTypeValue value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeInt(context.index().protos().indexOf(value.protoItem));
        }

        @Override
        public int size() {
            return 4;
        }
    };

    @Override
    public int type() {
        return 0x15;
    }
}
