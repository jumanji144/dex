package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBBCCCC;
import me.darknet.dex.file.instructions.FormatAGopBBBBFEDC;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.Nullable;

public final class FilledNewArrayInstruction implements Instruction {

    private final ClassType componentType;
    private final int[] registers;

    private final int size;
    private final int first;

    public FilledNewArrayInstruction(ClassType componentType, int... registers) {
        this.componentType = componentType;
        this.size = registers.length;
        this.first = registers[0];
        this.registers = registers;
        if (registers.length > 6) {
            // make sure they are in sequential order
            for (int i = 1; i < registers.length; i++) {
                if (registers[i] != registers[i - 1] + 1) {
                    throw new IllegalArgumentException("Registers must be in sequential order");
                }
            }
        }
    }

    public FilledNewArrayInstruction(ClassType componentType, int size, int first) {
        this.componentType = componentType;
        this.size = size;
        this.first = first;
        this.registers = null;
    }

    @Override
    public int opcode() {
        return registers == null ? FILLED_NEW_ARRAY_RANGE : FILLED_NEW_ARRAY;
    }

    public ClassType componentType() {
        return componentType;
    }

    /**
     * Returns the registers used to store the array values.
     * @return the registers used to store the array values, or {@code null} if this is a range instruction
     */
    public int @Nullable [] registers() {
        return registers;
    }

    public boolean isRange() {
        return registers == null;
    }

    public int first() {
        return first;
    }

    public int last() {
        return registers == null ? first + size - 1 : registers[size - 1];
    }

    @Override
    public String toString() {
        if (isRange()) {
            return "filled-new-array/range " + componentType + ", v" + first + " ... v" + last();
        } else {
            StringBuilder sb = new StringBuilder("filled-new-array " + componentType + ", {v");
            sb.append(first);
            for (int i = 1; i < registers.length; i++) {
                sb.append(", v").append(registers[i]);
            }
            sb.append('}');
            return sb.toString();
        }
    }

    public static final InstructionCodec<FilledNewArrayInstruction, Format> CODEC = new InstructionCodec<>() {

        @Override
        public FilledNewArrayInstruction map(Format input, InstructionContext<DexMap> context) {
            return switch (input) {
                case FormatAGopBBBBFEDC(int op, int a, int b, int c, int d, int e, int f, int g) -> {
                    int[] shrunk = new int[a];
                    System.arraycopy(new int[] {c, d, e, f, g}, 0, shrunk, 0, a);
                    ClassType componentType = Types.classType(context.map().types().get(b));
                    yield new FilledNewArrayInstruction(componentType, shrunk);
                }
                case FormatAAopBBBBCCCC(int op, int a, int b, int c) -> {
                    ClassType componentType = Types.classType(context.map().types().get(b));
                    yield new FilledNewArrayInstruction(componentType, a, c);
                }
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public Format unmap(FilledNewArrayInstruction output, InstructionContext<DexMapBuilder> context) {
            int type = context.map().addType(output.componentType);
            if (output.registers == null) {
                return new FormatAAopBBBBCCCC(FILLED_NEW_ARRAY_RANGE, output.size, type, output.first);
            }

            int[] stretched = new int[5];
            System.arraycopy(output.registers, 0, stretched, 0, output.size);

            return new FormatAGopBBBBFEDC(FILLED_NEW_ARRAY, output.size, type,
                    stretched[0], stretched[1], stretched[2], stretched[3], stretched[4]);
        }
    };

    @Override
    public int byteSize() {
        return 3;
    }
}
