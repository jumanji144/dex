package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.FormatBAop;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.OpcodeNames;
import me.darknet.dex.tree.type.PrimitiveType;

public record Binary2AddrInstruction(int opcode, int a, int b) implements Instruction {

    public Binary2AddrInstruction(int kind, PrimitiveType type, int a, int b) {
        this(Opcodes.ADD_INT_2ADDR + BinaryOperation.operation(kind, type), a, b);
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode()) + " v" + a + ", v" + b;
    }

    public static final InstructionCodec<Binary2AddrInstruction, FormatBAop> CODEC = new InstructionCodec<>() {
        @Override
        public FormatBAop unmap(Binary2AddrInstruction output) {
            return new FormatBAop(output.opcode(), output.a(), output.b());
        }

        @Override
        public Binary2AddrInstruction map(FormatBAop input) {
            return new Binary2AddrInstruction(input.op(), input.a(), input.b());
        }
    };
}
