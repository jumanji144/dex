package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;

public record CheckCastInstruction(int register, ClassType type) implements Instruction {

    @Override
    public int opcode() {
        return CHECK_CAST;
    }

    @Override
    public String toString() {
        return "check-cast v" + register + ", " + type.descriptor();
    }

    public static final InstructionCodec<CheckCastInstruction, FormatAAopBBBB> CODEC = new InstructionCodec<>() {
        @Override
        public CheckCastInstruction map(FormatAAopBBBB input, InstructionContext<DexMap> ctx) {
            return new CheckCastInstruction(input.a(), Types.classType(ctx.map().types().get(input.b())));
        }

        @Override
        public FormatAAopBBBB unmap(CheckCastInstruction output, InstructionContext<DexMapBuilder> ctx) {
            int type = ctx.map().addType(output.type());
            return new FormatAAopBBBB(CHECK_CAST, output.register(), type);
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
