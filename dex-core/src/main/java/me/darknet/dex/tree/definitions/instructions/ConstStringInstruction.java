package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record ConstStringInstruction(int register, String string) implements Instruction {

    @Override
    public int opcode() {
        // only return CONST_STRING, as CONST_STRING_JUMBO can only be determined at write-time
        return CONST_STRING;
    }

    @Override
    public String toString() {
        return "const-string v" + register + ", \"" + string + "\"";
    }

    public static final InstructionCodec<ConstStringInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull ConstStringInstruction map(@NotNull Format input, @NotNull InstructionContext<DexMap> context) {
            return switch (input) {
                case FormatAAopBBBB(int op, int a, int b) ->
                        new ConstStringInstruction(a, context.map().strings().get(b).string());
                case FormatAAopBBBB32(int op, int a, int b) ->
                        new ConstStringInstruction(a, context.map().strings().get(b).string());
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public @NotNull Format unmap(@NotNull ConstStringInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int index = context.map().addString(output.string);
            if (index <= 0xffff) {
                return new FormatAAopBBBB(CONST_STRING, output.register(), index);
            } else {
                return new FormatAAopBBBB32(CONST_STRING_JUMBO, output.register(), index);
            }
        }
    };

    @Override
    public int byteSize() {
        return 2; // NOTE: this is not accurate, but the actual size is determined at write-time
    }
}
