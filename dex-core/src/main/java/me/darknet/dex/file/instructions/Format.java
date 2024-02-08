package me.darknet.dex.file.instructions;

import me.darknet.dex.io.Codec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

/**
 * Denotes an instruction format.
 */
public interface Format extends Opcodes {

    int op();

    Codec<Format> CODEC = new Codec<>() {

        @Override
        public Format read(Input input) throws IOException {
            int first = input.readShort();
            FormatCodec<?> codec = switch (first) {
                case 0x0100 -> FormatPackedSwitch.CODEC;
                case 0x0200 -> FormatSparseSwitch.CODEC;
                case 0x0300 -> FormatFilledArrayData.CODEC;
                default -> Formats.get(first & 0xFF);
            };
            if (codec == null) {
                throw new IOException("Unknown opcode: " + Integer.toHexString(first)
                        + " at " + Integer.toHexString(input.position()));
            }
            input.seek(-2); // go back
            return codec.read(input);
        }

        @Override
        public void write(Format value, Output output) throws IOException {
            int op = value.op();
            FormatCodec codec = switch (op) {
                case 0x0100 -> FormatPackedSwitch.CODEC;
                case 0x0200 -> FormatSparseSwitch.CODEC;
                case 0x0300 -> FormatFilledArrayData.CODEC;
                default -> Formats.get(op);
            };
            if (codec == null) {
                throw new IOException("Unknown opcode: " + Integer.toHexString(op)
                        + " at " + Integer.toHexString(output.position()));
            }
            codec.write(value, output);
        }
    };


}
