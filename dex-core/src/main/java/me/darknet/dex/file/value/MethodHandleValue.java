package me.darknet.dex.file.value;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.MethodHandleItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record MethodHandleValue(MethodHandleItem item) implements Value {

    public static final ValueCodec<MethodHandleValue> CODEC = new ValueCodec<>() {
        @Override
        public MethodHandleValue read(Input input, DexMapAccess context) throws IOException {
            return new MethodHandleValue(context.methodHandles().get((int) input.readUnsignedInt()));
        }

        @Override
        public void write(MethodHandleValue value, Output output, DexMapAccess context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeInt(context.methodHandles().indexOf(value.item));
        }

        @Override
        public int size() {
            return 4;
        }
    };

    @Override
    public int type() {
        return 0x16;
    }
}
