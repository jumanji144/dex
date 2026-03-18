package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.instructions.FormatAAopBBBB64;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record ConstWideInstruction(int opcode, int register, long value) implements Instruction {
    private static final long HIGH16_MASK = 0xffffL << 48;

    private static int op(long value) {
        // Determine which opcode to use
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) // 16 bit signed const
            return CONST_WIDE_16;
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) // 32 bit signed const
            return CONST_WIDE_32;
        if ((value & ~HIGH16_MASK) == 0) // bits are only set in the high 16 bits
            return CONST_WIDE_HIGH16;
        return CONST_WIDE;
    }

    private static long decodeConst16(int opcode, int value) {
        if (opcode == CONST_WIDE_HIGH16) {
            return ((long) value) << 48;
        }
        return value;
    }

    public ConstWideInstruction(int register, long value) {
        this(op(value), register, value);
    }

    @Override
    public String toString() {
        return "const-wide v" + register + ", " + value;
    }

    public static final InstructionCodec<ConstWideInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull ConstWideInstruction map(@NotNull Format input, @NotNull InstructionContext<DexMap> context) {
            return switch (input) {
                case FormatAAopBBBB(int op, int a, int b) -> new ConstWideInstruction(op, a, decodeConst16(op, b));
                case FormatAAopBBBB32(int op, int a, int b) -> new ConstWideInstruction(op, a, b);
                case FormatAAopBBBB64(int op, int a, long b) -> new ConstWideInstruction(op, a, b);
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public @NotNull Format unmap(@NotNull ConstWideInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return switch (output.opcode()) {
                case CONST_WIDE_16 -> new FormatAAopBBBB(CONST_WIDE_16, output.register(), (int) output.value());
                case CONST_WIDE_32 -> new FormatAAopBBBB32(CONST_WIDE_32, output.register(), (int) output.value());
                case CONST_WIDE_HIGH16 -> new FormatAAopBBBB(CONST_WIDE_HIGH16, output.register(), (int) (output.value() >> 48));
                case CONST_WIDE -> new FormatAAopBBBB64(CONST_WIDE, output.register(), output.value());
                default -> throw new IllegalArgumentException("Unmappable opcode: " + output.opcode());
            };
        }
    };

    @Override
    public int byteSize() {
        return switch (opcode) {
            case CONST_WIDE_16 -> 2;
            case CONST_WIDE_32, CONST_WIDE_HIGH16 -> 4;
            case CONST_WIDE -> 8;
            default -> throw new IllegalArgumentException("Invalid opcode: " + opcode);
        };
    }
}
