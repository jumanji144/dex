package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format00op;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record NopInstruction() implements Instruction {

    @Override
    public int opcode() {
        return NOP;
    }

    @Override
    public String toString() {
        return "nop";
    }

    public static final InstructionCodec<NopInstruction, Format00op> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull NopInstruction map(@NotNull Format00op input, @NotNull InstructionContext<DexMap> context) {
            return new NopInstruction();
        }

        @Override
        public @NotNull Format00op unmap(@NotNull NopInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return new Format00op(NOP);
        }
    };

}
