package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopCCBB;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.OpcodeNames;
import me.darknet.dex.tree.type.PrimitiveType;
import org.jetbrains.annotations.NotNull;

public record BinaryInstruction(int opcode, int dest, int a, int b) implements Instruction {

    public BinaryInstruction(int kind, PrimitiveType type, int dest, int a, int b) {
        this(Opcodes.ADD_INT + BinaryOperation.operation(kind, type), dest, a, b);
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode()) + " v" + dest + ", v" + a + ", v" + b;
    }

    public static final InstructionCodec<BinaryInstruction, FormatAAopCCBB> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull BinaryInstruction map(@NotNull FormatAAopCCBB input, @NotNull InstructionContext<DexMap> context) {
            return new BinaryInstruction(input.op(), input.a(), input.b(), input.c());
        }

        @Override
        public @NotNull FormatAAopCCBB unmap(@NotNull BinaryInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return new FormatAAopCCBB(output.opcode(), output.dest(), output.a(), output.b());
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
