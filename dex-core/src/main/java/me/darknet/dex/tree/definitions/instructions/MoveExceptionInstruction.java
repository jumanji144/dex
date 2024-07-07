package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAop;

import java.util.Objects;

public record MoveExceptionInstruction(int register) implements Instruction {

    @Override
    public int opcode() {
        return MOVE_EXCEPTION;
    }

    public static final InstructionCodec<MoveExceptionInstruction, FormatAAop> CODEC = new InstructionCodec<>() {
        @Override
        public MoveExceptionInstruction map(FormatAAop input) {
            return new MoveExceptionInstruction(input.a());
        }

        @Override
        public FormatAAop unmap(MoveExceptionInstruction output) {
            return new FormatAAop(MOVE_EXCEPTION, output.register());
        }
    };

}
