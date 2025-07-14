package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatBAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record ArrayLengthInstruction(int dest, int array) implements Instruction {
    @Override
    public int opcode() {
        return ARRAY_LENGTH;
    }

    @Override
    public String toString() {
        return "array-length v" + dest + ", v" + array;
    }

    public static final InstructionCodec<ArrayLengthInstruction, FormatBAop> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull ArrayLengthInstruction map(@NotNull FormatBAop input, @NotNull InstructionContext<DexMap> ctx) {
            return new ArrayLengthInstruction(input.a(), input.b());
        }

        @Override
        public @NotNull FormatBAop unmap(@NotNull ArrayLengthInstruction output, @NotNull InstructionContext<DexMapBuilder> ctx) {
            return new FormatBAop(ARRAY_LENGTH, output.dest(), output.array());
        }
    };
}
