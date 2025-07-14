package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.Format00opAAAABBBB;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.FormatBAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record MoveInstruction(int opcode, int to, int from) implements Instruction {

    public static int op(int to, int from) {
        if (to <= 0xf && from <= 0xf)
            return MOVE;
        if (to <= 0xff && from <= 0xffff)
            return MOVE_FROM16;
        return MOVE_16; // 16 bit registers
    }

    public MoveInstruction(int to, int from) {
        this(op(to, from), to, from);
    }

    @Override
    public String toString() {
        return "move v" + to + ", v" + from;
    }

    public static final InstructionCodec<MoveInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull MoveInstruction map(@NotNull Format input, @NotNull InstructionContext<DexMap> context) {
            return switch (input) {
                case FormatBAop(int op, int a, int b) -> new MoveInstruction(op, a, b);
                case FormatAAopBBBB(int op, int a, int b) -> new MoveInstruction(op, a, b);
                case Format00opAAAABBBB(int op, int a, int b) -> new MoveInstruction(op, a, b);
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public @NotNull Format unmap(@NotNull MoveInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return switch (output.opcode()) {
                case MOVE -> new FormatBAop(MOVE, output.to(), output.from());
                case MOVE_FROM16 -> new FormatAAopBBBB(MOVE_FROM16, output.to(), output.from());
                case MOVE_16 -> new Format00opAAAABBBB(MOVE_16, output.to(), output.from());
                default -> throw new IllegalArgumentException("Unmappable opcode: " + output.opcode());
            };
        }
    };

    @Override
    public int byteSize() {
        return switch (opcode) {
            case MOVE -> 1;
            case MOVE_FROM16 -> 2;
            case MOVE_16 -> 3;
            default -> throw new IllegalArgumentException("Invalid opcode: " + opcode);
        };
    }
}
