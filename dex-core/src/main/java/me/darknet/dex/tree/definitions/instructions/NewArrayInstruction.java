package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatBAopCCCC;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;

public record NewArrayInstruction(int dest, int sizeRegister, ClassType componentType) implements Instruction {
    @Override
    public int opcode() {
        return NEW_ARRAY;
    }

    @Override
    public String toString() {
        return "new-array v" + dest + ", v" + sizeRegister + ", " + componentType.descriptor();
    }

    public static final InstructionCodec<NewArrayInstruction, FormatBAopCCCC> CODEC = new InstructionCodec<>() {
        @Override
        public NewArrayInstruction map(FormatBAopCCCC input, Context<DexMap> ctx) {
            return new NewArrayInstruction(input.a(), input.b(), Types.classType(ctx.map().types().get(input.c())));
        }

        @Override
        public FormatBAopCCCC unmap(NewArrayInstruction output, Context<DexMapBuilder> ctx) {
            int type = ctx.map().addType(output.componentType());
            return new FormatBAopCCCC(NEW_ARRAY, output.dest(), output.sizeRegister(), type);
        }
    };
}
