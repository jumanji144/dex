package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

public record ConstTypeInstruction(int register, ClassType type) implements Instruction {

    @Override
    public int opcode() {
        return CONST_CLASS;
    }

    @Override
    public String toString() {
        return "const-class v" + register + ", " + type.descriptor();
    }

    public static final InstructionCodec<ConstTypeInstruction, FormatAAopBBBB> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull ConstTypeInstruction map(@NotNull FormatAAopBBBB input, @NotNull InstructionContext<DexMap> context) {
            return new ConstTypeInstruction(input.a(), Types.classType(context.map().types().get(input.ub())));
        }

        @Override
        public @NotNull FormatAAopBBBB unmap(@NotNull ConstTypeInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int type = context.map().addType(output.type());
            return new FormatAAopBBBB(CONST_CLASS, output.register(), type);
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
