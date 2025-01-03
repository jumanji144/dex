package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;

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
        public ConstTypeInstruction map(FormatAAopBBBB input, InstructionContext<DexMap> context) {
            return new ConstTypeInstruction(input.a(), Types.classType(context.map().types().get(input.b())));
        }

        @Override
        public FormatAAopBBBB unmap(ConstTypeInstruction output, InstructionContext<DexMapBuilder> context) {
            int type = context.map().addType(output.type());
            return new FormatAAopBBBB(CONST_CLASS, output.register(), type);
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
