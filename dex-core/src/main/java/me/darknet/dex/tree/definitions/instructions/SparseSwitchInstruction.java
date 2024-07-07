package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.instructions.FormatSparseSwitch;

import java.util.HashMap;
import java.util.Map;

public record SparseSwitchInstruction(Map<Integer, Label> targets) implements Instruction {
    @Override
    public int opcode() {
        return SPARSE_SWITCH;
    }

    @Override
    public String toString() {
        return "sparse-switch " + targets.keySet().stream()
                .map(Object::toString).reduce((a, b) -> a + " -> " + b).orElse("");
    }

    public static final InstructionCodec<SparseSwitchInstruction, FormatAAopBBBB32> CODEC = new InstructionCodec<>() {
        @Override
        public SparseSwitchInstruction map(FormatAAopBBBB32 input, Context<DexMap> context) {
            FormatSparseSwitch payload = context.sparseSwitchPayload(input, input.b());
            Map<Integer, Label> targets = new HashMap<>(payload.targets().length);
            for (int i = 0; i < payload.targets().length; i++) {
                targets.put(payload.keys()[i], context.label(payload, payload.targets()[i]));
            }

            return new SparseSwitchInstruction(targets);
        }

        @Override
        public FormatAAopBBBB32 unmap(SparseSwitchInstruction output, Context<DexMapBuilder> context) {
            int[] keys = new int[output.targets.size()];
            int[] targets = new int[output.targets.size()];
            int i = 0;
            for (Map.Entry<Integer, Label> entry : output.targets.entrySet()) {
                keys[i] = entry.getKey();
                targets[i] = entry.getValue().offset();
                i++;
            }

            FormatSparseSwitch payload = new FormatSparseSwitch(keys, targets);

            int offset = context.sparseSwitchPayloads().get(payload);

            return new FormatAAopBBBB32(SPARSE_SWITCH, 0, offset);
        }
    };
}
