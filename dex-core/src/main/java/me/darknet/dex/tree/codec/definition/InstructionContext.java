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

    /**
     * Finds the label for the given instruction and offset, throwing an exception if the instruction is not found in the context or if no instruction is found at the target offset.
     * @param format Format of the instruction to find the label for.
     * @param offset Offset from the instruction to the target label.
     * @return Label for the target offset from the given instruction.
     */
    public @NotNull Label label(@NotNull Format format, int offset) {
        int thisIndex = instructionIndex(format);
        int thisPosition = offsets.get(thisIndex);

        int targetPosition = thisPosition + offset;

        int targetIndex = offsets.indexOf(targetPosition);
        if (targetIndex == -1)
            throw new IllegalArgumentException("No instruction found for offset: " + offset);

        return computeLabel(targetPosition, targetIndex);
    }

    /**
     * Finds the offset from the given instruction to the target label, throwing an exception if the instruction is not found in the context.
     * @param instruction Instruction to find the offset from.
     * @param label Target label to find the offset to.
     * @return Offset from the given instruction to the target label.
     */
    public int labelOffset(@NotNull Instruction instruction, @NotNull Label label) {
        int thisIndex = instructionIndex(instruction);
        int thisPosition = offsets.get(thisIndex);

        int targetPosition = label.position();

        return targetPosition - thisPosition;
    }

    /**
     * Finds the label for the given offset, throwing an exception if no instruction is found at the target offset.
     * @param offset Target offset.
     * @return Label for the target offset.
     */
    public @NotNull Label label(int offset) {
        int targetIndex = offsets.indexOf(offset);
        if (targetIndex == -1)
            throw new IllegalArgumentException("No instruction found for offset: " + offset);

        return computeLabel(offset, targetIndex);
    }

    /**
     * Finds the label for the given offset, or the closest preceding label if an exact match is not found.
     * @param offset Target offset.
     * @return Label for the target offset, or the closest preceding label if an exact match is not found.
     */
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

        return computeLabel(offset, targetIndex);
    }

    private @NotNull Label computeLabel(int offset, int targetIndex) {
        return labels.computeIfAbsent(offset, o -> new Label(targetIndex, o));
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
        int thisIndex = instructionIndex(format);
        int thisPosition = offsets.get(thisIndex);

        int targetPosition = thisPosition + offset;

        int targetIndex = offsets.indexOf(targetPosition);
        if (targetIndex == -1)
            throw new IllegalArgumentException("No instruction found for offset: " + offset);

        return targetIndex;
    }

    private int instructionIndex(@NotNull Object instruction) {
        for (int i = 0; i < instructions.size(); i++)
            if (instructions.get(i) == instruction)
                return i;
        throw new IllegalArgumentException("Instruction '" + instruction + "' not found in context");
    }
}
