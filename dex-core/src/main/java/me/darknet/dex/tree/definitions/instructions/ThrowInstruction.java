package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;

public record ThrowInstruction(int value) implements Instruction {
    @Override
    public int opcode() {
        return THROW;
    }

    @Override
    public String toString() {
        return "throw v" + value;
    }

    public static final InstructionCodec<ThrowInstruction, FormatAAop> CODEC = new InstructionCodec<>() {
        @Override
        public ThrowInstruction map(FormatAAop input, InstructionContext<DexMap> context) {
            return new ThrowInstruction(input.a());
        }

        @Override
        public FormatAAop unmap(ThrowInstruction output, InstructionContext<DexMapBuilder> context) {
            return new FormatAAop(THROW, output.value());
        }
    };
}
