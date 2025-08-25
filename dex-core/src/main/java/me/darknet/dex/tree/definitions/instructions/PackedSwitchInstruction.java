package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.instructions.FormatPackedSwitch;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

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
        public @NotNull PackedSwitchInstruction map(@NotNull FormatAAopBBBB32 input, @NotNull InstructionContext<DexMap> context) {
            FormatPackedSwitch payload = context.packedSwitchPayload(input, input.b());
            List<Label> targets = new ArrayList<>(payload.targets().length);
            for (int target : payload.targets()) {
                targets.add(context.label(input, target));
            }

            return new PackedSwitchInstruction(input.a(), targets);
        }

        @Override
        public @NotNull FormatAAopBBBB32 unmap(@NotNull PackedSwitchInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int offset = context.packedSwitchPayloads().get(output);

            return new FormatAAopBBBB32(PACKED_SWITCH, output.first, offset);
        }
    };

    @Override
    public int byteSize() {
        return 4;
    }
}
