package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public record FormatSparseSwitch(int[] keys, int[] targets) implements PseudoFormat {
    @Override
    public int op() {
        return P_SPARSE_SWITCH;
    }

    public static final FormatCodec<FormatSparseSwitch> CODEC = new FormatCodec<>() {
        @Override
        public @NotNull FormatSparseSwitch read(@NotNull Input input) throws IOException {
            input.readUnsignedShort(); // discard
            int size = input.readShort();
            int[] keys = new int[size];
            int[] targets = new int[size];
            for (int i = 0; i < size; i++) {
                keys[i] = input.readInt();
            }
            for (int i = 0; i < size; i++) {
                targets[i] = input.readInt();
            }
            return new FormatSparseSwitch(keys, targets);
        }

        @Override
        public void write(@NotNull FormatSparseSwitch value, @NotNull Output output) throws IOException {
            output.writeShort(value.op());
            int size = value.keys().length;
            output.writeShort(size);
            for (int key : value.keys()) {
                output.writeInt(key);
            }
            for (int target : value.targets()) {
                output.writeInt(target);
            }
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FormatSparseSwitch that = (FormatSparseSwitch) o;
        return Objects.deepEquals(keys, that.keys) && Objects.deepEquals(targets, that.targets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(keys), Arrays.hashCode(targets));
    }

    @Override
    public int size() {
        return 2 + keys.length * 2 + targets.length * 2;
    }
}
