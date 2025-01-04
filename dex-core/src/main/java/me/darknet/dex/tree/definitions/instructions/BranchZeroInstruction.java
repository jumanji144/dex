package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.OpcodeNames;

public record BranchZeroInstruction(int kind, int a, Label label) implements Instruction {
    @Override
    public int opcode() {
        return Opcodes.IF_EQZ + kind;
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode()) + " v" + a + ", " + label;
    }

    public static final InstructionCodec<BranchZeroInstruction, FormatAAopBBBB> CODEC = new InstructionCodec<>() {
        @Override
        public BranchZeroInstruction map(FormatAAopBBBB input, InstructionContext<DexMap> context) {
            return new BranchZeroInstruction(input.op() - Opcodes.IF_EQZ, input.a(), context.label(input, input.b()));
        }

        @Override
        public FormatAAopBBBB unmap(BranchZeroInstruction output, InstructionContext<DexMapBuilder> context) {
            return new FormatAAopBBBB(output.opcode(), output.a(), (short) context.labelOffset(output, output.label));
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
