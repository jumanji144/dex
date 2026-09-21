package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.items.ProtoItem;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

public record ConstMethodTypeInstruction(int destination, MethodType type) implements Instruction {
    @Override
    public int opcode() {
        return CONST_METHOD_TYPE;
    }

    @Override
    public String toString() {
        return "const-method-type v" + destination + ", " + type;
    }

    public static final InstructionCodec<ConstMethodTypeInstruction, FormatAAopBBBB> CODEC = new InstructionCodec<>() {

        @Override
        public @NotNull ConstMethodTypeInstruction map(@NotNull FormatAAopBBBB input, @NotNull InstructionContext<DexMap> context) {
            // The operand indexes the prototype pool, not the type pool: the instruction carries a method
            // prototype, which is what distinguishes it from const-class.
            ProtoItem proto = context.map().protos().get(input.b());
            return new ConstMethodTypeInstruction(input.a(), Types.methodType(proto));
        }

        @Override
        public @NotNull FormatAAopBBBB unmap(@NotNull ConstMethodTypeInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int protoIndex = context.map().addProto(output.type());
            return new FormatAAopBBBB(CONST_METHOD_TYPE, output.destination(), protoIndex);
        }

    };
}
