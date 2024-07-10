package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.FormatBAop;
import me.darknet.dex.tree.definitions.OpcodeNames;
import me.darknet.dex.tree.type.PrimitiveType;

public record UnaryInstruction(int opcode, int source, int dest) implements Instruction {

    public UnaryInstruction(int operation, PrimitiveType type, int source, int dest) {
        this(UnaryOperation.operation(operation, type), source, dest);
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode) + " v" + dest + ", v" + source;
    }

    public static final InstructionCodec<UnaryInstruction, FormatBAop> CODEC = new InstructionCodec<>() {
        @Override
        public UnaryInstruction map(FormatBAop input) {
            return new UnaryInstruction(input.op(), input.a(), input.b());
        }

        @Override
        public FormatBAop unmap(UnaryInstruction output) {
            return new FormatBAop(output.opcode(), output.source(), output.dest());
        }
    };
}
