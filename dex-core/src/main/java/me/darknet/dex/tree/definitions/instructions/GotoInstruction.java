package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.Format00opAAAA;
import me.darknet.dex.file.instructions.Format00opAAAA32;
import me.darknet.dex.file.instructions.FormatAAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record GotoInstruction(int opcode, Label jump) implements Instruction {

    /**
     * Picks the narrowest goto form that can hold the given branch offset.
     *
     * @param offset
     * 		Signed offset to the target, in code units.
     *
     * @return Opcode of the narrowest form that can represent the offset.
     */
    public static int op(int offset) {
        // The offset fields are signed, so the 10t form can only reach [-128, 127].
        //
        // Comparing against an unsigned limit would let 128-255 through, and a negative
        // offset always satisfies an unsigned comparison, so both directions would silently truncate on encode.
        if (offset >= Byte.MIN_VALUE && offset <= Byte.MAX_VALUE)
            return GOTO;
        if (offset >= Short.MIN_VALUE && offset <= Short.MAX_VALUE)
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
        public @NotNull GotoInstruction map(@NotNull Format input, @NotNull InstructionContext<DexMap> context) {
            return switch (input) {
                case FormatAAop(int op, int a) -> new GotoInstruction(op, context.label(input, (byte) a));
                case Format00opAAAA(int op, int a) -> new GotoInstruction(op, context.label(input, (short) a));
                case Format00opAAAA32(int op, int a) -> new GotoInstruction(op, context.label(input, a));
                default -> throw new IllegalArgumentException("Invalid format: " + input);
            };
        }

        @Override
        public @NotNull Format unmap(@NotNull GotoInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
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
    public int unitSize() {
        return switch (opcode) {
            case GOTO -> 1;
            case GOTO_16 -> 2;
            case GOTO_32 -> 3;
            default -> throw new IllegalArgumentException("Invalid opcode: " + opcode);
        };
    }
}
