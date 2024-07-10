package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.*;
import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.tree.codec.ContextMappingCodec;

import java.util.Map;

public interface Instruction extends Opcodes {

    int opcode();

    record Context<T extends DexMapAccess>(CodeItem code, T map,
                                           Map<Integer, Label> labels,
                                           Map<FormatFilledArrayData, Integer> arrayPayloads,
                                           Map<FormatPackedSwitch, Integer> packedSwitchPayloads,
                                           Map<FormatSparseSwitch, Integer> sparseSwitchPayloads) {

        Label label(Format format, int offset) {
            int thisIndex = code.instructions().indexOf(format);
            int thisPosition = code.offsets().get(thisIndex);

            int targetPosition = thisPosition + offset;

            int targetIndex = code.offsets().indexOf(targetPosition);
            if (targetIndex == -1)
                throw new IllegalArgumentException("No instruction found for offset: " + offset);

            Label target = new Label(targetIndex, targetPosition);
            labels.put(targetPosition, target);
            return target;
        }

        FormatFilledArrayData arrayPayload(Format format, int offset) {
            int targetIndex = lookup(format, offset);

            return (FormatFilledArrayData) code.instructions().get(targetIndex);
        }

        FormatPackedSwitch packedSwitchPayload(Format format, int offset) {
            int targetIndex = lookup(format, offset);

            return (FormatPackedSwitch) code.instructions().get(targetIndex);
        }

        FormatSparseSwitch sparseSwitchPayload(Format format, int offset) {
            int targetIndex = lookup(format, offset);

            return (FormatSparseSwitch) code.instructions().get(targetIndex);
        }

        private int lookup(Format format, int offset) {
            int thisIndex = code.instructions().indexOf(format);
            int thisPosition = code.offsets().get(thisIndex);

            int targetPosition = thisPosition + offset;

            int targetIndex = code.offsets().indexOf(targetPosition);
            if (targetIndex == -1)
                throw new IllegalArgumentException("No instruction found for offset: " + offset);

            return targetIndex;
        }

    }

    interface InstructionCodec<I extends Instruction, F extends Format>
            extends ContextMappingCodec<F, I, Context<DexMap>, Context<DexMapBuilder>> {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    InstructionCodec<Instruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public Instruction map(Format input, Context<DexMap> context) {
            InstructionCodec codec = Instructions.CODECS.get(input.op());
            if (codec == null) {
                return null;
                //throw new IllegalArgumentException("Unmappable format: " + input);
            }
            return (Instruction) codec.map(input, context);
        }

        @Override
        public Format unmap(Instruction output, Context<DexMapBuilder> context) {
            InstructionCodec codec = Instructions.CODECS.get(output.opcode());
            if (codec == null) {
                //throw new IllegalArgumentException("Unmappable opcode: " + output.opcode());
            }
            return (Format) codec.unmap(output, context);
        }
    };
}
