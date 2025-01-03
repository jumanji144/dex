package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.Format00opAAAABBBB;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.FormatBAop;

public record MoveObjectInstruction(int opcode, int to, int from) implements Instruction {

    public static int op(int to, int from) {
        if (to <= 0xf && from <= 0xf)
            return MOVE_OBJECT;
        if (to <= 0xff && from <= 0xffff)
            return MOVE_OBJECT_FROM16;
        return MOVE_OBJECT_16; // 16 bit registers
    }

    public MoveObjectInstruction(int to, int from) {
        this(op(to, from), to, from);
    }

    @Override
    public String toString() {
        return "move-object v" + to + ", v" + from;
    }

    public static final InstructionCodec<MoveObjectInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public MoveObjectInstruction map(Format input) {
            return switch (input) {
                case FormatBAop(int op, int a, int b) -> new MoveObjectInstruction(op, a, b);
                case FormatAAopBBBB(int op, int a, int b) -> new MoveObjectInstruction(op, a, b);
                case Format00opAAAABBBB(int op, int a, int b) -> new MoveObjectInstruction(op, a, b);
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public Format unmap(MoveObjectInstruction output) {
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
            case MOVE_OBJECT -> 1;
            case MOVE_OBJECT_FROM16 -> 2;
            case MOVE_OBJECT_16 -> 3;
            default -> throw new IllegalArgumentException("Invalid opcode: " + opcode);
        };
    }
}
