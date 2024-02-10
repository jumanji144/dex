package me.darknet.dex.file.value;

import me.darknet.dex.codecs.DexCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

public interface Value {

    interface ValueCodec<T extends Value> extends DexCodec<T> {
        default int size() {
            return 1;
        }
    }

    Map<Integer, ValueCodec<?>> CODECS = Map.ofEntries(
      Map.entry(0x00, ByteValue.CODEC), Map.entry(0x02, ShortValue.CODEC),
        Map.entry(0x03, CharValue.CODEC), Map.entry(0x04, IntValue.CODEC),
        Map.entry(0x06, LongValue.CODEC), Map.entry(0x10, FloatValue.CODEC),
        Map.entry(0x11, DoubleValue.CODEC), Map.entry(0x15, MethodTypeValue.CODEC),
        Map.entry(0x16, MethodHandleValue.CODEC), Map.entry(0x17, StringValue.CODEC),
        Map.entry(0x18, TypeValue.CODEC), Map.entry(0x19, FieldValue.CODEC),
        Map.entry(0x1a, MethodValue.CODEC), Map.entry(0x1b, EnumValue.CODEC),
        Map.entry(0x1c, ArrayValue.CODEC), Map.entry(0x1d, AnnotationValue.CODEC),
        Map.entry(0x1e, NullValue.CODEC), Map.entry(0x1f, BoolValue.CODEC)
    );

    int type();

    DexCodec<Value> CODEC = new DexCodec<>() {

        private Input zeroExtend(Input input, int size, int targetSize) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(targetSize).order(input.order());
            buffer.put(input.readBytes(size));
            buffer.position(0);
            return Input.wrap(buffer);
        }

        @Override
        public Value read(Input input, DexMapAccess context) throws IOException {
            int value = input.readUnsignedByte();
            // (value_arg << 5) | value_type
            int value_type = value & 0x1f;
            int value_arg = value >> 5;
            ValueCodec<?> codec = CODECS.get(value_type);
            Input passed = codec.size() == 0 ? input : zeroExtend(input, value_arg+1, codec.size());
            return codec.read(passed, context);
        }

        @Override
        public void write(Value value, Output output, WriteContext context) throws IOException {
            int type = value.type();
            ValueCodec codec = CODECS.get(type);
            codec.write(value, output, context);
        }
    };

}
