package me.darknet.dex.tree.codec.definition;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatFilledArrayData;
import me.darknet.dex.file.instructions.FormatPackedSwitch;
import me.darknet.dex.file.instructions.FormatSparseSwitch;
import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.tree.definitions.instructions.Label;

import java.util.Map;

public record InstructionContext<T extends DexMapAccess>(CodeItem code, T map,
                                                         Map<Integer, Label> labels,
                                                         Map<FormatFilledArrayData, Integer> arrayPayloads,
                                                         Map<FormatPackedSwitch, Integer> packedSwitchPayloads,
                                                         Map<FormatSparseSwitch, Integer> sparseSwitchPayloads) {

    public Label label(Format format, int offset) {
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

    public Label label(int offset) {
        int targetIndex = code.offsets().indexOf(offset);
        if (targetIndex == -1)
            throw new IllegalArgumentException("No instruction found for offset: " + offset);

        Label target = new Label(targetIndex, offset);
        labels.put(offset, target);
        return target;
    }

    public FormatFilledArrayData arrayPayload(Format format, int offset) {
        int targetIndex = lookup(format, offset);

        return (FormatFilledArrayData) code.instructions().get(targetIndex);
    }

    public FormatPackedSwitch packedSwitchPayload(Format format, int offset) {
        int targetIndex = lookup(format, offset);

        return (FormatPackedSwitch) code.instructions().get(targetIndex);
    }

    public FormatSparseSwitch sparseSwitchPayload(Format format, int offset) {
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
