package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.items.TypeItem;
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

    public static final InstructionCodec<ConstTypeInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public ConstTypeInstruction map(Format input, Context<DexMap> context) {
            if (input instanceof FormatAAopBBBB(int op, int a, int b)) {
                return new ConstTypeInstruction(a, Types.classType(context.map().types().get(b)));
            }
            throw new IllegalArgumentException("Unmappable format: " + input);
        }

        @Override
        public Format unmap(ConstTypeInstruction output, Context<DexMapBuilder> context) {
            int type = context.map().addType(output.type());
            return new FormatAAopBBBB(CONST_CLASS, output.register(), type);
        }
    };
}
