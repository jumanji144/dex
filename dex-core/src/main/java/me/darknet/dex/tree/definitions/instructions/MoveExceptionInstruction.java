package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;


public record MoveExceptionInstruction(int register) implements Instruction {

    @Override
    public int opcode() {
        return MOVE_EXCEPTION;
    }

    public static final InstructionCodec<MoveExceptionInstruction, FormatAAop> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull MoveExceptionInstruction map(@NotNull FormatAAop input, @NotNull InstructionContext<DexMap> context) {
            return new MoveExceptionInstruction(input.a());
        }

        @Override
        public @NotNull FormatAAop unmap(@NotNull MoveExceptionInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return new FormatAAop(MOVE_EXCEPTION, output.register());
        }
    };

}
