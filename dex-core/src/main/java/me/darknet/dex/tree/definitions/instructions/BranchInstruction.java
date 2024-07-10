package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatBAopCCCC;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.OpcodeNames;

public record BranchInstruction(int test, int a, int b, Label label) implements Instruction {

    @Override
    public int opcode() {
        return Opcodes.IF_EQ + test;
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode()) + " v" + a + ", v" + b + ", " + label;
    }

    public static final InstructionCodec<BranchInstruction, FormatBAopCCCC> CODEC = new InstructionCodec<>() {
        @Override
        public BranchInstruction map(FormatBAopCCCC input, Context<DexMap> context) {
            return new BranchInstruction(input.op() - Opcodes.IF_EQ, input.a(), input.b(),
                    context.label(input, input.c()));
        }

        @Override
        public FormatBAopCCCC unmap(BranchInstruction output, Context<DexMapBuilder> context) {
            return new FormatBAopCCCC(output.opcode(), output.a(), output.b(), output.label.offset());
        }
    };
}
