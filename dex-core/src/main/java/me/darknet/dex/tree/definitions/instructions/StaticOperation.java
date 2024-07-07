package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.items.FieldItem;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;

public record StaticOperation(int kind, int value, InstanceType owner, String name, ClassType type)
        implements Instruction {
    @Override
    public int opcode() {
        return SGET + kind;
    }

    @Override
    public String toString() {
        return "s" + Operation.name(kind) + " v" + value + ", " + owner.internalName() + "." + name + " " + type;
    }

    public static final InstructionCodec<StaticOperation, FormatAAopBBBB> CODEC = new InstructionCodec<>() {

        @Override
        public StaticOperation map(FormatAAopBBBB input, Context<DexMap> context) {
            FieldItem field = context.map().fields().get(input.b());
            InstanceType owner = Types.instanceType(field.owner());
            String name = field.name().string();
            ClassType type = Types.classType(field.type());
            return new StaticOperation(input.op() - SGET, input.a(), owner, name, type);
        }

        @Override
        public FormatAAopBBBB unmap(StaticOperation output, Context<DexMapBuilder> context) {
            int field = context.map().addField(output.owner, output.name, output.type);
            return new FormatAAopBBBB(output.opcode(), output.value(), field);
        }
    };

}
