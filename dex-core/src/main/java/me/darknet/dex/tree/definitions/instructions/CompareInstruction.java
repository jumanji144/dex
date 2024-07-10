package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.FormatAAopCCBB;
import me.darknet.dex.tree.definitions.OpcodeNames;

public record CompareInstruction(int opcode, int dest, int a, int b) implements Instruction {

    @Override
    public String toString() {
        return "cmp" + OpcodeNames.name(opcode) + " v" + dest + ", v" + a + ", v" + b;
    }

    public static final InstructionCodec<CompareInstruction, FormatAAopCCBB> CODEC = new InstructionCodec<>() {
        @Override
        public CompareInstruction map(FormatAAopCCBB input) {
            return new CompareInstruction(input.op(), input.a(), input.b(), input.c());
        }

        @Override
        public FormatAAopCCBB unmap(CompareInstruction output) {
            return new FormatAAopCCBB(output.opcode(), output.dest(), output.a(), output.b());
        }
    };
}
