package me.darknet.dex.file.value;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

public interface Value {

    interface ValueCodec<T extends Value> extends ContextCodec<T, DexMapAccess> {
        default int size() {
            return 1;
        }
    }

    Map<Integer, ValueCodec<?>> CODECS = Map.ofEntries(
      Map.entry(0x00, ByteValue.CODEC) // ByteValue
    );

    int type();

    ContextCodec<Value, DexMapAccess> CODEC = new ContextCodec<>() {

        private Input zeroExtend(Input input, int size, int targetSize) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(targetSize);
            buffer.put(input.readBytes(size));
            return Input.wrap(buffer);
        }

        @Override
        public Value read(Input input, DexMapAccess context) throws IOException {
            int value = input.readUnsignedByte();
            // encoding, value = (byte) (size << 5 | type)
            int type = value & 0x1f;
            int size = value >> 5;
            ValueCodec<?> codec = CODECS.get(type);
            Input passed = codec.size() == 0 ? input : zeroExtend(input, size, codec.size());
            return codec.read(passed, context);
        }

        @Override
        public void write(Value value, Output output, DexMapAccess context) throws IOException {
            int type = value.type();
            ValueCodec codec = CODECS.get(type);
            codec.write(value, output, context);
        }
    };

}
