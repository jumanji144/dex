package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.instructions.FormatPackedSwitch;
import me.darknet.dex.tree.codec.definition.InstructionContext;

import java.util.ArrayList;
import java.util.List;

public record PackedSwitchInstruction(int first, List<Label> targets) implements Instruction {
    @Override
    public int opcode() {
        return PACKED_SWITCH;
    }

    @Override
    public String toString() {
        return "packed-switch " + first + " -> " + targets.stream()
                .map(Label::toString).reduce((a, b) -> a + ", " + b).orElse("");
    }

    public static final InstructionCodec<PackedSwitchInstruction, FormatAAopBBBB32> CODEC = new InstructionCodec<>() {
        @Override
        public PackedSwitchInstruction map(FormatAAopBBBB32 input, InstructionContext<DexMap> context) {
            FormatPackedSwitch payload = context.packedSwitchPayload(input, input.b());
            List<Label> targets = new ArrayList<>(payload.targets().length);
            for (int target : payload.targets()) {
                targets.add(context.label(payload, target));
            }

            return new PackedSwitchInstruction(input.a(), targets);
        }

        @Override
        public FormatAAopBBBB32 unmap(PackedSwitchInstruction output, InstructionContext<DexMapBuilder> context) {
            int[] targets = new int[output.targets.size()];
            for (int i = 0; i < output.targets.size(); i++) {
                targets[i] = output.targets.get(i).offset();
            }

            FormatPackedSwitch payload = new FormatPackedSwitch(output.first, targets);

            int offset = context.packedSwitchPayloads().get(payload);

            return new FormatAAopBBBB32(PACKED_SWITCH, output.first, offset);
        }
    };

    @Override
    public int byteSize() {
        return 4;
    }
}
