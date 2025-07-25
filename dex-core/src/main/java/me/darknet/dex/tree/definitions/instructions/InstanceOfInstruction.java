package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatBAopCCCC;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

public record InstanceOfInstruction(int destination, int register, ClassType type) implements Instruction {
    @Override
    public int opcode() {
        return INSTANCE_OF;
    }

    @Override
    public String toString() {
        return "instance-of v" + destination + ", v" + register + ", " + type.descriptor();
    }

    public static final InstructionCodec<InstanceOfInstruction, FormatBAopCCCC> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull InstanceOfInstruction map(@NotNull FormatBAopCCCC input, @NotNull InstructionContext<DexMap> context) {
            return new InstanceOfInstruction(input.a(), input.b(), Types.classType(context.map().types().get(input.c())));
        }

        @Override
        public @NotNull FormatBAopCCCC unmap(@NotNull InstanceOfInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int type = context.map().addType(output.type());
            return new FormatBAopCCCC(INSTANCE_OF, output.destination(), output.register(), type);
        }
    };

    @Override
    public int byteSize() {
        return 2;
    }
}
