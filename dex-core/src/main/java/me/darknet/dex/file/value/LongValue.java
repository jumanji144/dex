package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record LongValue(long value) implements Value {

    public static final ValueCodec<LongValue> CODEC = new ValueCodec<>() {

        @Override
        public LongValue read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            return new LongValue(input.readLong());
        }

        @Override
        public void write(LongValue value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeByte(((size() - 1) << 5) | value.type());
            output.writeLong(value.value);
        }

        @Override
        public int size() {
            return 8;
        }
    };

    @Override
    public int type() {
        return 0x06;
    }

}
