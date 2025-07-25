package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.*;
import me.darknet.dex.tree.codec.ContextMappingCodec;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public interface Instruction extends Opcodes {

    /**
     * @return the opcode of the instruction.
     * @see Opcodes
     */
    int opcode();

    /**
     * @return the size of the instruction in bytes.
     * @see Format#size()
     */
    default int byteSize() {
        return 1;
    }


    interface InstructionCodec<I extends Instruction, F extends Format>
            extends ContextMappingCodec<F, I, InstructionContext<DexMap>, InstructionContext<DexMapBuilder>> {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    InstructionCodec<Instruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull Instruction map(@NotNull Format input, @NotNull InstructionContext<DexMap> context) {
            InstructionCodec codec = Instructions.CODECS.get(input.op());
            if (codec == null) {
                throw new IllegalArgumentException("Unmappable format: 0x" + Integer.toHexString(input.op()) + " - " + input);
            }
            return (Instruction) codec.map(input, context);
        }

        @Override
        public @NotNull Format unmap(@NotNull Instruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            InstructionCodec codec = Instructions.CODECS.get(output.opcode());
            if (codec == null) {
                throw new IllegalArgumentException("Unmappable opcode: 0x" + Integer.toHexString(output.opcode()) + " - " + output.opcode());
            }
            return (Format) codec.unmap(output, context);
        }

    };
}
