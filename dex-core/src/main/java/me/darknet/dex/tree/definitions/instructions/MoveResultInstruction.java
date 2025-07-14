package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record MoveResultInstruction(int opcode, int type, int to) implements Instruction {

    public static int op(int type) {
        return switch (type) {
            case Result.NORMAL -> MOVE_RESULT;
            case Result.WIDE -> MOVE_RESULT_WIDE;
            case Result.OBJECT -> MOVE_RESULT_OBJECT;
            default -> throw new IllegalArgumentException("Invalid type: " + type);
        };
    }

    public MoveResultInstruction(int type, int to) {
        this(op(type), type, to);
    }

    public MoveResultInstruction(int to) {
        this(MOVE_RESULT, Result.NORMAL, to);
    }

    @Override
    public String toString() {
        return switch (type) {
            case Result.NORMAL -> "move-result v" + to;
            case Result.WIDE -> "move-result-wide v" + to;
            case Result.OBJECT -> "move-result-object v" + to;
            default -> throw new IllegalArgumentException("Invalid type: " + type);
        };
    }

    public static final InstructionCodec<MoveResultInstruction, FormatAAop> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull MoveResultInstruction map(@NotNull FormatAAop input, @NotNull InstructionContext<DexMap> context) {
            return switch (input.op()) {
                case MOVE_RESULT -> new MoveResultInstruction(Result.NORMAL, input.a());
                case MOVE_RESULT_WIDE -> new MoveResultInstruction(Result.WIDE, input.a());
                case MOVE_RESULT_OBJECT -> new MoveResultInstruction(Result.OBJECT, input.a());
                default -> throw new IllegalArgumentException("Unmappable opcode: " + input.op());
            };
        }

        @Override
        public @NotNull FormatAAop unmap(@NotNull MoveResultInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return new FormatAAop(output.opcode(), output.to());
        }
    };

}
