package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Codec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FormatPackedSwitch(int first, int[] targets) implements Format {
    @Override
    public int op() {
        return 0x0100;
    }

    public static final FormatCodec<FormatPackedSwitch> CODEC = new FormatCodec<>() {
        @Override
        public FormatPackedSwitch read(Input input) throws IOException {
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
        public void write(FormatPackedSwitch value, Output output) throws IOException {
            output.writeShort(value.op());
            int size = value.targets().length;
            output.writeShort(size);
            output.writeInt(value.first());
            for (int target : value.targets()) {
                output.writeInt(target);
            }
        }
    };
}
