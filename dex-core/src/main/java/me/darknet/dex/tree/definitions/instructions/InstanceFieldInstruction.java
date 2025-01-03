package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatBAopCCCC;
import me.darknet.dex.file.items.FieldItem;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.OpcodeNames;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;

public record InstanceFieldInstruction(int kind, int value, int instance, InstanceType owner, String name, ClassType type)
        implements Instruction {
    @Override
    public int opcode() {
        return IGET + kind;
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode()) + " v" + value + ", v" + instance + ", "
                + owner.internalName() + "." + name + " " + type;
    }

    public static final InstructionCodec<InstanceFieldInstruction, FormatBAopCCCC> CODEC = new InstructionCodec<>() {

        @Override
        public InstanceFieldInstruction map(FormatBAopCCCC input, InstructionContext<DexMap> context) {
            FieldItem field = context.map().fields().get(input.c());
            InstanceType owner = Types.instanceType(field.owner());
            String name = field.name().string();
            ClassType type = Types.classType(field.type());
            return new InstanceFieldInstruction(input.op() - IGET, input.a(), input.b(), owner, name, type);
        }

        @Override
        public FormatBAopCCCC unmap(InstanceFieldInstruction output, InstructionContext<DexMapBuilder> context) {
            int field = context.map().addField(output.owner, output.name, output.type);
            return new FormatBAopCCCC(output.opcode(), output.value(), output.instance(), field);
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
