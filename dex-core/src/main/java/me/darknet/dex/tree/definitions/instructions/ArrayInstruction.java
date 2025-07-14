package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopCCBB;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.OpcodeNames;
import org.jetbrains.annotations.NotNull;

public record ArrayInstruction(int kind, int value, int array, int index) implements Instruction {
    @Override
    public int opcode() {
        return AGET + kind;
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode()) + " v" + value + ", v" + array + ", v" + index;
    }

    public static final InstructionCodec<ArrayInstruction, FormatAAopCCBB> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull ArrayInstruction map(@NotNull FormatAAopCCBB input, @NotNull InstructionContext<DexMap> context) {
            return new ArrayInstruction(input.op() - AGET, input.a(), input.b(), input.c());
        }

        @Override
        public @NotNull FormatAAopCCBB unmap(@NotNull ArrayInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return new FormatAAopCCBB(output.opcode(), output.value(), output.array(), output.index());
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
