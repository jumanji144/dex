package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopCCBB;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.OpcodeNames;
import org.jetbrains.annotations.NotNull;

public record CompareInstruction(int opcode, int dest, int a, int b) implements Instruction {

    @Override
    public String toString() {
        return "cmp" + OpcodeNames.name(opcode) + " v" + dest + ", v" + a + ", v" + b;
    }

    public static final InstructionCodec<CompareInstruction, FormatAAopCCBB> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull CompareInstruction map(@NotNull FormatAAopCCBB input, @NotNull InstructionContext<DexMap> context) {
            return new CompareInstruction(input.op(), input.a(), input.b(), input.c());
        }

        @Override
        public @NotNull FormatAAopCCBB unmap(@NotNull CompareInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return new FormatAAopCCBB(output.opcode(), output.dest(), output.a(), output.b());
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
