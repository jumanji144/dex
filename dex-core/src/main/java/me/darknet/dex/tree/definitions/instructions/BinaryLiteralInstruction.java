package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopCCBB;
import me.darknet.dex.file.instructions.FormatBAopCCCC;
import me.darknet.dex.file.instructions.Formats;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record BinaryLiteralInstruction(int opcode, int dest, int src, int constant) implements Instruction {

    private static int op(int kind, short constant) {
        if (constant >= Byte.MIN_VALUE && constant <= Byte.MAX_VALUE)
            return kind + Opcodes.ADD_INT_LIT8;
        return kind + Opcodes.ADD_INT_LIT16;
    }

    public BinaryLiteralInstruction(int kind, int dest, int src, short constant) {
        this(op(kind, constant), dest, src, (int) constant);
    }

    public static final InstructionCodec<BinaryLiteralInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull BinaryLiteralInstruction map(@NotNull Format input, @NotNull InstructionContext<DexMap> context) {
            return switch (input) {
                // The value of 'c' is sign-extended when decoded, so we need to cast it back to the original type to get the correct value
                case FormatAAopCCBB(int op, int a, int b, int c) -> new BinaryLiteralInstruction(op, a, b, (int) (byte) c);
                case FormatBAopCCCC(int op, int a, int b, int c) -> new BinaryLiteralInstruction(op, a, b, (int) (short) c);
                default -> throw new IllegalArgumentException("Invalid format");
            };
        }

        @Override
        public @NotNull Format unmap(@NotNull BinaryLiteralInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            if (Formats.get(output.opcode) == FormatAAopCCBB.CODEC) {
                return new FormatAAopCCBB(output.opcode(), output.dest(), output.src(), output.constant());
            } else {
                return new FormatBAopCCCC(output.opcode(), output.dest(), output.src(), output.constant());
            }
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
