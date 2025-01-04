package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatBAopCCCC;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.codec.definition.InstructionContext;
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
        public BranchInstruction map(FormatBAopCCCC input, InstructionContext<DexMap> context) {
            return new BranchInstruction(input.op() - Opcodes.IF_EQ, input.a(), input.b(),
                    context.label(input, (short) input.c()));
        }

        @Override
        public FormatBAopCCCC unmap(BranchInstruction output, InstructionContext<DexMapBuilder> context) {
            return new FormatBAopCCCC(output.opcode(), output.a(), output.b(),
                    (short) context.labelOffset(output, output.label));
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
