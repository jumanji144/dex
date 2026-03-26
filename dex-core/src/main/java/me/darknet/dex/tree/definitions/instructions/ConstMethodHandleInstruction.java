package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.items.MethodHandleItem;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.constant.Handle;
import org.jetbrains.annotations.NotNull;

public record ConstMethodHandleInstruction(int destination, Handle handle) implements Instruction {
    @Override
    public int opcode() {
        return CONST_METHOD_HANDLE;
    }

    @Override
    public String toString() {
        return "const-method-handle v" + destination + ", " + handle;
    }

    public static final InstructionCodec<ConstMethodHandleInstruction, FormatAAopBBBB> CODEC = new InstructionCodec<>() {

        @Override
        public @NotNull ConstMethodHandleInstruction map(@NotNull FormatAAopBBBB input, @NotNull InstructionContext<DexMap> context) {
            MethodHandleItem handleItem = context.map().methodHandles().get(input.b());
            Handle handle = Handle.CODEC.map(handleItem, context.map());
            return new ConstMethodHandleInstruction(input.a(), handle);
        }

        @Override
        public @NotNull FormatAAopBBBB unmap(@NotNull ConstMethodHandleInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int methodHandle = context.map().addMethodHandle(output.handle());
            return new FormatAAopBBBB(CONST_METHOD_HANDLE, output.destination(), methodHandle);
        }
    };
}
