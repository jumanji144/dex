package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.items.FieldItem;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.OpcodeNames;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

public record StaticFieldInstruction(int kind, int value, InstanceType owner, String name, ClassType type)
        implements Instruction {
    @Override
    public int opcode() {
        return SGET + kind;
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode()) + " v" + value + ", " + owner.internalName() + "." + name + " " + type;
    }

    public static final InstructionCodec<StaticFieldInstruction, FormatAAopBBBB> CODEC = new InstructionCodec<>() {

        @Override
        public @NotNull StaticFieldInstruction map(@NotNull FormatAAopBBBB input, @NotNull InstructionContext<DexMap> context) {
            FieldItem field = context.map().fields().get(input.ub());
            InstanceType owner = Types.instanceType(field.owner());
            String name = field.name().string();
            ClassType type = Types.classType(field.type());
            return new StaticFieldInstruction(input.op() - SGET, input.a(), owner, name, type);
        }

        @Override
        public @NotNull FormatAAopBBBB unmap(@NotNull StaticFieldInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int field = context.map().addField(output.owner, output.name, output.type);
            return new FormatAAopBBBB(output.opcode(), output.value(), field);
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
