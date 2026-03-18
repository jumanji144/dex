package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record ConstStringInstruction(int opcode, int register, String string) implements Instruction {

    public ConstStringInstruction(int register, String string) {
        this(CONST_STRING, register, string);
    }

    @Override
    public int opcode() {
        return opcode;
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
                        new ConstStringInstruction(op, a, context.map().strings().get(b & 0xffff).string());
                case FormatAAopBBBB32(int op, int a, int b) ->
                        new ConstStringInstruction(op, a, context.map().strings().get(b).string());
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public @NotNull Format unmap(@NotNull ConstStringInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int index = context.map().addString(output.string);
            if (output.opcode == CONST_STRING_JUMBO || index > 0xffff) {
                return new FormatAAopBBBB32(CONST_STRING_JUMBO, output.register(), index);
            } else {
                return new FormatAAopBBBB(CONST_STRING, output.register(), index);
            }
        }
    };

    @Override
    public int byteSize() {
        return opcode == CONST_STRING_JUMBO ? 3 : 2;
    }
}
