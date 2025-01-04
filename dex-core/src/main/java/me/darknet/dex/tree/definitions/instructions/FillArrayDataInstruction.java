package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.instructions.FormatFilledArrayData;
import me.darknet.dex.tree.codec.definition.InstructionContext;

public record FillArrayDataInstruction(int array, byte[] data, int elementSize) implements Instruction {
    @Override
    public int opcode() {
        return FILL_ARRAY_DATA;
    }

    @Override
    public String toString() {
        return "fill-array-data " + array + ", " + data.length + " bytes / " + elementSize + " per element";
    }

    public static final InstructionCodec<FillArrayDataInstruction, FormatAAopBBBB32> CODEC = new InstructionCodec<>() {
        @Override
        public FillArrayDataInstruction map(FormatAAopBBBB32 input, InstructionContext<DexMap> context) {
            FormatFilledArrayData payload = context.arrayPayload(input, input.b());
            return new FillArrayDataInstruction(input.a(), payload.data(), payload.width());
        }

        @Override
        public FormatAAopBBBB32 unmap(FillArrayDataInstruction output, InstructionContext<DexMapBuilder> context) {
            int offset = context.arrayPayloads().get(output);

            return new FormatAAopBBBB32(FILL_ARRAY_DATA, output.array, offset);
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
