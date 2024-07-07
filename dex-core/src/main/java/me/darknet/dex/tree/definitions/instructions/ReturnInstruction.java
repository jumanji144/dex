package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.Format00op;
import me.darknet.dex.file.instructions.FormatAAop;

public record ReturnInstruction(int opcode, int register, int type) implements Instruction {

    public static int op(int type) {
        return switch (type) {
            case Return.NORMAL -> RETURN;
            case Return.WIDE -> RETURN_WIDE;
            case Return.OBJECT -> RETURN_OBJECT;
            case Return.VOID -> RETURN_VOID;
            default -> throw new IllegalArgumentException("Unmappable return type: " + type);
        };
    }

    /**
     * Creates a return instruction of the given type
     * @param register the register to return
     * @param type the type of the return
     */
    public ReturnInstruction(int register, int type) {
        this(op(type), register, type);
    }

    /**
     * Creates a normal return instruction
     * @param register the register to return
     */
    public ReturnInstruction(int register) {
        this(register, Return.NORMAL);
    }

    /**
     * Creates a return void instruction
     */
    public ReturnInstruction() {
        this(RETURN_VOID, -1, Return.VOID);
    }

    @Override
    public String toString() {
        return switch (type) {
            case Return.WIDE -> "return-wide v" + register;
            case Return.OBJECT -> "return-object v" + register;
            case Return.VOID -> "return-void";
            default -> "return v" + register;
        };
    }

    public static final InstructionCodec<ReturnInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public ReturnInstruction map(Format input) {
            return switch (input) {
                case FormatAAop(int op, int a) -> switch (op) {
                    case RETURN -> new ReturnInstruction(a, Return.NORMAL);
                    case RETURN_WIDE -> new ReturnInstruction(a, Return.WIDE);
                    case RETURN_OBJECT -> new ReturnInstruction(a, Return.OBJECT);
                    default -> throw new IllegalArgumentException("Unmappable opcode: " + op);
                };
                case Format00op(int op) -> new ReturnInstruction(op, -1, Return.VOID);
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public Format unmap(ReturnInstruction output) {
            return switch (output.type()) {
                case Return.NORMAL, Return.WIDE, Return.OBJECT -> new FormatAAop(output.opcode(), output.register());
                case Return.VOID -> new Format00op(output.opcode());
                default -> throw new IllegalArgumentException("Unmappable return type: " + output.type());
            };
        }
    };

}
