package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.Format00opAAAA;
import me.darknet.dex.file.instructions.Format00opAAAA32;
import me.darknet.dex.file.instructions.FormatAAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;

public record GotoInstruction(int opcode, Label jump) implements Instruction {

    public static int op(int offset) {
        if (offset <= 0xff)
            return GOTO;
        if (offset <= 0xffff)
            return GOTO_16;
        return GOTO_32;
    }

    public GotoInstruction(Label jump) {
        this(GOTO, jump);
    }

    @Override
    public String toString() {
        return "goto " + jump.index();
    }

    public static final InstructionCodec<GotoInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public GotoInstruction map(Format input, InstructionContext<DexMap> context) {
            return switch (input) {
                case FormatAAop(int op, int a) -> new GotoInstruction(op, context.label(input, (byte) a));
                case Format00opAAAA(int op, int a) -> new GotoInstruction(op, context.label(input, (short) a));
                case Format00opAAAA32(int op, int a) -> new GotoInstruction(op, context.label(input, a));
                default -> throw new IllegalArgumentException("Invalid format: " + input);
            };
        }

        @Override
        public Format unmap(GotoInstruction output, InstructionContext<DexMapBuilder> context) {
            int offset = context.labelOffset(output, output.jump);
            int opcode = op(offset);
            return switch (opcode) {
                case GOTO -> new FormatAAop(opcode, offset);
                case GOTO_16 -> new Format00opAAAA(opcode, offset);
                case GOTO_32 -> new Format00opAAAA32(opcode, offset);
                default -> throw new IllegalArgumentException("Invalid opcode: " + opcode);
            };
        }
    };

    @Override
    public int byteSize() {
        return switch (opcode) {
            case GOTO -> 1;
            case GOTO_16 -> 2;
            case GOTO_32 -> 4;
            default -> throw new IllegalArgumentException("Invalid opcode: " + opcode);
        };
    }
}
