package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record ArrayValue(List<Value> values) implements Value {

    public static final ValueCodec<ArrayValue> CODEC = new ValueCodec<>() {

        @Override
        public ArrayValue read(Input input, DexMapAccess context) throws IOException {
            int size = input.readULeb128();
            List<Value> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                values.add(Value.CODEC.read(input, context));
            }
            return new ArrayValue(values);
        }

        @Override
        public void write(ArrayValue value, Output output, WriteContext context) throws IOException {
            output.writeByte(value.type()); // 0 << 5 | 0x1c
            output.writeULeb128(value.values.size());
            for (Value v : value.values) {
                Value.CODEC.write(v, output, context);
            }
        }

        @Override
        public int size() {
            return 0;
        }
    };

    @Override
    public int type() {
        return 0x1c;
    }
}
