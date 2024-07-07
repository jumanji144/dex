package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Format00op;

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
        public NopInstruction map(Format00op input) {
            return new NopInstruction();
        }

        @Override
        public Format00op unmap(NopInstruction output) {
            return new Format00op(NOP);
        }
    };

}
