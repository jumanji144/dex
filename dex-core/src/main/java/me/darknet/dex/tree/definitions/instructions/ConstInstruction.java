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
        if (value >= -8 && value <= 7) // 4 bit signed const
            return CONST_4;
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) // 16 bit signed const
            return CONST_16;
        if ((value & 0xFFFF) == 0) // bits are only set in the high 16 bits
            return CONST_HIGH16;
        return CONST;
    }

    private static int decodeConst4(int value) {
        return (value << 28) >> 28;
    }

    private static int decodeConst16(int opcode, int value) {
        if (opcode == CONST_HIGH16) {
            return value << 16;
        }
        return value;
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
                case FormatBAop(int op, int a, int b) -> new ConstInstruction(op, a, decodeConst4(b));
                case FormatAAopBBBB(int op, int a, int b) -> new ConstInstruction(op, a, decodeConst16(op, b));
                case FormatAAopBBBB32(int op, int a, int b) -> new ConstInstruction(op, a, b);
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public @NotNull Format unmap(@NotNull ConstInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return switch (output.opcode()) {
                case CONST_4 -> new FormatBAop(CONST_4, output.register(), output.value());
                case CONST_16 -> new FormatAAopBBBB(CONST_16, output.register(), output.value());
                case CONST_HIGH16 -> new FormatAAopBBBB(CONST_HIGH16, output.register(), output.value() >> 16);
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
