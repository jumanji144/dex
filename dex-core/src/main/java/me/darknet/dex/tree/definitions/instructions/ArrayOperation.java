package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopCCBB;

public record ArrayOperation(int kind, int value, int array, int index) implements Instruction {
    @Override
    public int opcode() {
        return AGET + kind;
    }

    @Override
    public String toString() {
        return "a" + Operation.name(kind) + " v" + value + ", v" + array + ", v" + index;
    }

    public static final InstructionCodec<ArrayOperation, FormatAAopCCBB> CODEC = new InstructionCodec<>() {
        @Override
        public ArrayOperation map(FormatAAopCCBB input, Context<DexMap> context) {
            return new ArrayOperation(input.op() - AGET, input.a(), input.b(), input.c());
        }

        @Override
        public FormatAAopCCBB unmap(ArrayOperation output, Context<DexMapBuilder> context) {
            return new FormatAAopCCBB(output.opcode(), output.value(), output.array(), output.index());
        }
    };
}
