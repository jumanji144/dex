package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FormatFilledArrayData(int width, byte[] data) implements Format {

    @Override
    public int op() {
        return 0x0300;
    }

    public static final FormatCodec<FormatFilledArrayData> CODEC = new FormatCodec<>() {
        @Override
        public FormatFilledArrayData read(Input input) throws IOException {
            input.readUnsignedShort(); // discard
            int width = input.readUnsignedShort();
            int size = input.readInt();
            byte[] data = new byte[size * width];
            input.readFully(data);
            if((input.position() & 1) != 0) {
                input.readByte(); // padding
            }
            return new FormatFilledArrayData(width, data);
        }

        @Override
        public void write(FormatFilledArrayData value, Output output) throws IOException {
            int size = value.data().length / value.width();
            output.writeShort(value.op());
            output.writeShort(value.width());
            output.writeInt(size);
            output.write(value.data());
            if((output.position() & 1) != 0) {
                output.writeByte((byte) 0); // padding
            }
        }
    };

}
