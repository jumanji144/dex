package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.instructions.FormatSparseSwitch;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public record SparseSwitchInstruction(int register, Map<Integer, Label> targets) implements Instruction {
    @Override
    public int opcode() {
        return SPARSE_SWITCH;
    }

    @Override
    public String toString() {
        return "sparse-switch v" + register + ", " + targets.keySet().stream()
                .map(Object::toString).reduce((a, b) -> a + " -> " + b).orElse("");
    }

    public static final InstructionCodec<SparseSwitchInstruction, FormatAAopBBBB32> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull SparseSwitchInstruction map(@NotNull FormatAAopBBBB32 input, @NotNull InstructionContext<DexMap> context) {
            FormatSparseSwitch payload = context.sparseSwitchPayload(input, input.b());
            Map<Integer, Label> targets = new HashMap<>(payload.targets().length);
            for (int i = 0; i < payload.targets().length; i++) {
                targets.put(payload.keys()[i], context.label(input, payload.targets()[i]));
            }

            return new SparseSwitchInstruction(input.a(), targets);
        }

        @Override
        public @NotNull FormatAAopBBBB32 unmap(@NotNull SparseSwitchInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int offset = context.sparseSwitchPayloads().get(output);

            return new FormatAAopBBBB32(SPARSE_SWITCH, output.register, offset);
        }
    };

    @Override
    public int byteSize() {
        return 4;
    }
}
