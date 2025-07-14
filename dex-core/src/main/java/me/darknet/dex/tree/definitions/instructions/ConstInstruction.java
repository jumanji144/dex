package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.instructions.FormatBAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record ConstInstruction(int opcode, int register, int value) implements Instruction {

    private static int op(int value) {
        // determine which opcode to use
        if (value <= 0xf) // 4 bit const
            return CONST_4;
        if (value <= 0xffff) // 16 bit const
            return CONST_16;
        final int HIGH16_MASK = 0xffff0000;
        if ((value & HIGH16_MASK) == value) // bits are only set in the high 16 bits
            return CONST_HIGH16;
        return CONST;
    }

    public ConstInstruction(int register, int value) {
        this(op(value), register, value);
    }

    @Override
    public String toString() {
        return "const v" + register + ", " + value;
    }

    public static final InstructionCodec<ConstInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull ConstInstruction map(@NotNull Format input, @NotNull InstructionContext<DexMap> context) {
            return switch (input) {
                case FormatBAop(int op, int a, int b) -> new ConstInstruction(op, a, b);
                case FormatAAopBBBB(int op, int a, int b) -> new ConstInstruction(op, a, b);
                case FormatAAopBBBB32(int op, int a, int b) -> new ConstInstruction(op, a, b);
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public @NotNull Format unmap(@NotNull ConstInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return switch (output.opcode()) {
                case CONST_4 -> new FormatBAop(CONST_4, output.register(), output.value());
                case CONST_16 -> new FormatAAopBBBB(CONST_16, output.register(), output.value());
                case CONST_HIGH16 -> new FormatAAopBBBB(CONST_HIGH16, output.register(), output.value());
                case CONST -> new FormatAAopBBBB32(CONST, output.register(), output.value());
                default -> throw new IllegalArgumentException("Unmappable opcode: " + output.opcode());
            };
        }
    };

    @Override
    public int byteSize() {
        return switch (opcode) {
            case CONST_4 -> 1;
            case CONST_16, CONST_HIGH16 -> 2;
            case CONST -> 4;
            default -> throw new IllegalArgumentException("Invalid opcode: " + opcode);
        };
    }
}
