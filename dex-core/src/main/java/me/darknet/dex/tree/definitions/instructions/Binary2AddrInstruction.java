package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatBAop;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.OpcodeNames;
import me.darknet.dex.tree.type.PrimitiveType;
import org.jetbrains.annotations.NotNull;

public record Binary2AddrInstruction(int opcode, int a, int b) implements Instruction {

    public Binary2AddrInstruction(int kind, PrimitiveType type, int a, int b) {
        this(Opcodes.ADD_INT_2ADDR + BinaryOperation.operation(kind, type), a, b);
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode()) + " v" + a + ", v" + b;
    }

    public static final InstructionCodec<Binary2AddrInstruction, FormatBAop> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull Binary2AddrInstruction map(@NotNull FormatBAop input, @NotNull InstructionContext<DexMap> context) {
            return new Binary2AddrInstruction(input.op(), input.a(), input.b());
        }

        @Override
        public @NotNull FormatBAop unmap(@NotNull Binary2AddrInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return new FormatBAop(output.opcode(), output.a(), output.b());
        }
    };
}
