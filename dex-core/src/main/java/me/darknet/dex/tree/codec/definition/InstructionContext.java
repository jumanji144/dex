package me.darknet.dex.tree.codec.definition;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatFilledArrayData;
import me.darknet.dex.file.instructions.FormatPackedSwitch;
import me.darknet.dex.file.instructions.FormatSparseSwitch;
import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public record InstructionContext<T extends DexMapAccess>(@NotNull List<? extends Object> instructions,
                                                         @NotNull List<Integer> offsets,
                                                         @NotNull T map,
                                                         @NotNull Map<Integer, Label> labels,
                                                         @Nullable Map<FillArrayDataInstruction, Integer> arrayPayloads,
                                                         @Nullable Map<PackedSwitchInstruction, Integer> packedSwitchPayloads,
                                                         @Nullable Map<SparseSwitchInstruction, Integer> sparseSwitchPayloads) {

    public @NotNull Label label(@NotNull Format format, int offset) {
        int thisIndex = instructions.indexOf(format);
        int thisPosition = offsets.get(thisIndex);

        int targetPosition = thisPosition + offset;

        int targetIndex = offsets.indexOf(targetPosition);
        if (targetIndex == -1)
            throw new IllegalArgumentException("No instruction found for offset: " + offset);

        Label target = new Label(targetIndex, targetPosition);
        labels.put(targetPosition, target);
        return target;
    }

    public int labelOffset(@NotNull Instruction instruction, @NotNull Label label) {
        int thisIndex = instructions.indexOf(instruction);
        int thisPosition = offsets.get(thisIndex);

        int targetPosition = label.position();

        return targetPosition - thisPosition;
    }

    public @NotNull Label label(int offset) {
        int targetIndex = offsets.indexOf(offset);
        if (targetIndex == -1)
            throw new IllegalArgumentException("No instruction found for offset: " + offset);

        Label target = new Label(targetIndex, offset);
        labels.put(offset, target);
        return target;
    }

    public @NotNull Label labelInexact(int offset) {
        int targetIndex = offsets.indexOf(offset);
        if (targetIndex == -1) {
            // Find the closest offset less than the target offset
            int closestIndex = -1;
            int closestOffset = -1;
            for (int i = 0; i < offsets.size(); i++) {
                int currentOffset = offsets.get(i);
                if (currentOffset < offset && currentOffset > closestOffset) {
                    closestOffset = currentOffset;
                    closestIndex = i;
                }
            }
            if (closestIndex == -1) {
                throw new IllegalArgumentException("No instruction found for offset: " + offset);
            }

            targetIndex = closestIndex;
        }

        Label target = new Label(targetIndex, offset);
        labels.put(offset, target);
        return target;
    }

    public @NotNull FormatFilledArrayData arrayPayload(@NotNull Format format, int offset) {
        int targetIndex = lookup(format, offset);

        return (FormatFilledArrayData) instructions.get(targetIndex);
    }

    public @NotNull FormatPackedSwitch packedSwitchPayload(@NotNull Format format, int offset) {
        int targetIndex = lookup(format, offset);

        return (FormatPackedSwitch) instructions.get(targetIndex);
    }

    public @NotNull FormatSparseSwitch sparseSwitchPayload(@NotNull Format format, int offset) {
        int targetIndex = lookup(format, offset);

        return (FormatSparseSwitch) instructions.get(targetIndex);
    }

    private int lookup(@NotNull Format format, int offset) {
        int thisIndex = instructions.indexOf(format);
        int thisPosition = offsets.get(thisIndex);

        int targetPosition = thisPosition + offset;

        int targetIndex = offsets.indexOf(targetPosition);
        if (targetIndex == -1)
            throw new IllegalArgumentException("No instruction found for offset: " + offset);

        return targetIndex;
    }

}
