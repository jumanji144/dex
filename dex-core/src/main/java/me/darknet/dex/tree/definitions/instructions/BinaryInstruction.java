package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.FormatAAopCCBB;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.OpcodeNames;
import me.darknet.dex.tree.type.PrimitiveType;

public record BinaryInstruction(int opcode, int dest, int a, int b) implements Instruction {

    public BinaryInstruction(int kind, PrimitiveType type, int dest, int a, int b) {
        this(Opcodes.ADD_INT + BinaryOperation.operation(kind, type), dest, a, b);
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode()) + " v" + dest + ", v" + a + ", v" + b;
    }

    public static final InstructionCodec<BinaryInstruction, FormatAAopCCBB> CODEC = new InstructionCodec<>() {
        @Override
        public FormatAAopCCBB unmap(BinaryInstruction output) {
            return new FormatAAopCCBB(output.opcode(), output.dest(), output.a(), output.b());
        }

        @Override
        public BinaryInstruction map(FormatAAopCCBB input) {
            return new BinaryInstruction(input.op(), input.a(), input.b(), input.c());
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
