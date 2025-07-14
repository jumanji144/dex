package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public record FormatPackedSwitch(int first, int[] targets) implements Format {
    @Override
    public int op() {
        return 0x0100;
    }

    public static final FormatCodec<FormatPackedSwitch> CODEC = new FormatCodec<>() {
        @Override
        public @NotNull FormatPackedSwitch read(@NotNull Input input) throws IOException {
            input.readUnsignedShort(); // discard
            int size = input.readShort();
            int first = input.readInt();
            int[] targets = new int[size];
            for (int i = 0; i < size; i++) {
                targets[i] = input.readInt();
            }
            return new FormatPackedSwitch(first, targets);
        }

        @Override
        public void write(@NotNull FormatPackedSwitch value, @NotNull Output output) throws IOException {
            output.writeShort(value.op());
            int size = value.targets().length;
            output.writeShort(size);
            output.writeInt(value.first());
            for (int target : value.targets()) {
                output.writeInt(target);
            }
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FormatPackedSwitch that = (FormatPackedSwitch) o;
        return first == that.first && Objects.deepEquals(targets, that.targets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, Arrays.hashCode(targets));
    }

    @Override
    public int size() {
        return 1 + 1 + 2 + targets.length * 2;
    }
}
