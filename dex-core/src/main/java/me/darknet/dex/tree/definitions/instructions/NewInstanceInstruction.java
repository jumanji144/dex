package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;

public record NewInstanceInstruction(int dest, InstanceType type) implements Instruction {
    @Override
    public int opcode() {
        return NEW_INSTANCE;
    }

    @Override
    public String toString() {
        return "new-instance v" + dest + ", " + type.descriptor();
    }

    public static final InstructionCodec<NewInstanceInstruction, FormatAAopBBBB> CODEC = new InstructionCodec<>() {
        @Override
        public NewInstanceInstruction map(FormatAAopBBBB input, Context<DexMap> ctx) {
            return new NewInstanceInstruction(input.a(), Types.instanceType(ctx.map().types().get(input.b())));
        }

        @Override
        public FormatAAopBBBB unmap(NewInstanceInstruction output, Context<DexMapBuilder> ctx) {
            int type = ctx.map().addType(output.type());
            return new FormatAAopBBBB(NEW_INSTANCE, output.dest(), type);
        }
    };
}
