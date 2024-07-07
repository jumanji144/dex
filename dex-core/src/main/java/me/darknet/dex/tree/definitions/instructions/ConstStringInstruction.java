package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.items.StringDataItem;
import me.darknet.dex.file.items.StringItem;

public record ConstStringInstruction(int register, String string) implements Instruction {

    @Override
    public int opcode() {
        // only return CONST_STRING, as CONST_STRING_JUMBO can only be determined at write-time
        return CONST_STRING;
    }

    public static final InstructionCodec<ConstStringInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public ConstStringInstruction map(Format input, Context<DexMap> context) {
            return switch (input) {
                case FormatAAopBBBB(int op, int a, int b) ->
                        new ConstStringInstruction(a, context.map().strings().get(b).string());
                case FormatAAopBBBB32(int op, int a, int b) ->
                        new ConstStringInstruction(a, context.map().strings().get(b).string());
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public Format unmap(ConstStringInstruction output, Context<DexMapBuilder> context) {
            int index = context.map().strings().add(new StringItem(new StringDataItem(output.string())));
            if (index <= 0xffff) {
                return new FormatAAopBBBB(CONST_STRING, output.register(), index);
            } else {
                return new FormatAAopBBBB32(CONST_STRING_JUMBO, output.register(), index);
            }
        }
    };

    @Override
    public String toString() {
        return "const-string v" + register + ", \"" + string + "\"";
    }
}
