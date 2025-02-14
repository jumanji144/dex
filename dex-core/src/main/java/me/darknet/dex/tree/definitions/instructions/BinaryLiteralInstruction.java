package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopCCBB;
import me.darknet.dex.file.instructions.FormatBAopCCCC;
import me.darknet.dex.file.instructions.Opcodes;

public record BinaryLiteralInstruction(int opcode, int dest, int src, int constant) implements Instruction {

    private static int op(int opcode, short constant) {
        if (constant <= 0xFF) {
            return opcode;
        }
        return opcode + Opcodes.ADD_INT_LIT8; // jump to bin_int_LIT16
    }

    public BinaryLiteralInstruction(int kind, int dest, int src, short constant) {
        this(op(kind + Opcodes.ADD_INT_LIT8, constant), dest, src, (int) constant);
    }

    public static final InstructionCodec<BinaryLiteralInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public Format unmap(BinaryLiteralInstruction output) {
            if (output.constant() <= 0xFF) {
                return new FormatAAopCCBB(output.opcode(), output.dest(), output.src(), output.constant());
            } else {
                return new FormatBAopCCCC(output.opcode(), output.dest(), output.src(), output.constant());
            }
        }

        @Override
        public BinaryLiteralInstruction map(Format input) {
            return switch (input) {
                case FormatAAopCCBB(int op, int a, int b, int c) -> new BinaryLiteralInstruction(op, a, b, c);
                case FormatBAopCCCC(int op, int a, int b, int c) -> new BinaryLiteralInstruction(op, a, b, c);
                default -> throw new IllegalArgumentException("Invalid format");
            };
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
