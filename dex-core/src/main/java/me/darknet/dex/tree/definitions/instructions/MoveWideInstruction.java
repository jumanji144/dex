package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.Format00opAAAABBBB;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.FormatBAop;

public record MoveWideInstruction(int opcode, int to, int from) implements Instruction {
    
    public static int op(int to, int from) {
        if (to <= 0xf && from <= 0xf)
            return MOVE_WIDE;
        if (to <= 0xff && from <= 0xffff)
            return MOVE_WIDE_FROM16;
        return MOVE_WIDE_16; // 16 bit registers
    }

    public MoveWideInstruction(int to, int from) {
        this(op(to, from), to, from);
    }

    @Override
    public String toString() {
        return "move-wide v" + to + ", v" + from;
    }

    public static final InstructionCodec<MoveWideInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public MoveWideInstruction map(Format input) {
            return switch (input) {
                case FormatBAop(int op, int a, int b) -> new MoveWideInstruction(op, a, b);
                case FormatAAopBBBB(int op, int a, int b) -> new MoveWideInstruction(op, a, b);
                case Format00opAAAABBBB(int op, int a, int b) -> new MoveWideInstruction(op, a, b);
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public Format unmap(MoveWideInstruction output) {
            return switch (output.opcode()) {
                case MOVE -> new FormatBAop(MOVE, output.to(), output.from());
                case MOVE_FROM16 -> new FormatAAopBBBB(MOVE_FROM16, output.to(), output.from());
                case MOVE_16 -> new Format00opAAAABBBB(MOVE_16, output.to(), output.from());
                default -> throw new IllegalArgumentException("Unmappable opcode: " + output.opcode());
            };
        }
    };
    
}
