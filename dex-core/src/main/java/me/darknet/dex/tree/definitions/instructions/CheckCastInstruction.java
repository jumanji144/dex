package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.items.TypeItem;
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
        public CheckCastInstruction map(FormatAAopBBBB input, Context<DexMap> ctx) {
            if (input instanceof FormatAAopBBBB(int op, int a, int b)) {
                return new CheckCastInstruction(a, Types.classType(ctx.map().types().get(b)));
            }
            throw new IllegalArgumentException("Unmappable format: " + input);
        }

        @Override
        public FormatAAopBBBB unmap(CheckCastInstruction output, Context<DexMapBuilder> ctx) {
            int type = ctx.map().addType(output.type());
            return new FormatAAopBBBB(CHECK_CAST, output.register(), type);
        }
    };
}
