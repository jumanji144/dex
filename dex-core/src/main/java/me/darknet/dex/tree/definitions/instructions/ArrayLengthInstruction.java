package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatBAop;

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
        public ArrayLengthInstruction map(FormatBAop input, Context<DexMap> ctx) {
            return new ArrayLengthInstruction(input.a(), input.b());
        }

        @Override
        public FormatBAop unmap(ArrayLengthInstruction output, Context<DexMapBuilder> ctx) {
            return new FormatBAop(ARRAY_LENGTH, output.dest(), output.array());
        }
    };
}
