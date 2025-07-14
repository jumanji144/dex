package me.darknet.dex.file.instructions;

import me.darknet.dex.codecs.Codec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Denotes an instruction format.
 */
public interface Format extends Opcodes {

    int op();

    /**
     * @return the size of this format in units (shorts)
     */
    default int size() {
        return 1;
    }

    Codec<Format> CODEC = new Codec<>() {

        @Override
        public @NotNull Format read(@NotNull Input input) throws IOException {
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
        public void write(@NotNull Format value, @NotNull Output output) throws IOException {
            int op = value.op();
            FormatCodec codec = switch (op) {
                case 0x0100 -> FormatPackedSwitch.CODEC;
                case 0x0200 -> FormatSparseSwitch.CODEC;
                case 0x0300 -> FormatFilledArrayData.CODEC;
                default -> Formats.get(op);
            };
            if (codec == null) {
                throw new IOException("Unknown opcode: 0x" + Integer.toHexString(op) +
                        " at 0x" + Integer.toHexString(output.position()));
            }
            codec.write(value, output);
        }
    };


}
