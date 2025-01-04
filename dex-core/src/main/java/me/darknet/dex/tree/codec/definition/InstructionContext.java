package me.darknet.dex.tree.codec.definition;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatFilledArrayData;
import me.darknet.dex.file.instructions.FormatPackedSwitch;
import me.darknet.dex.file.instructions.FormatSparseSwitch;
import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.*;

import java.util.List;
import java.util.Map;

public record InstructionContext<T extends DexMapAccess>(List<? extends Object> instructions, List<Integer> offsets,
                                                         T map, Map<Integer, Label> labels,
                                                         Map<FillArrayDataInstruction, Integer> arrayPayloads,
                                                         Map<PackedSwitchInstruction, Integer> packedSwitchPayloads,
                                                         Map<SparseSwitchInstruction, Integer> sparseSwitchPayloads) {

    public Label label(Format format, int offset) {
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

    public int labelOffset(Instruction instruction, Label label) {
        int thisIndex = instructions.indexOf(instruction);
        int thisPosition = offsets.get(thisIndex);

        int targetPosition = label.position();

        return targetPosition - thisPosition;
    }

    public Label label(int offset) {
        int targetIndex = offsets.indexOf(offset);
        if (targetIndex == -1)
            throw new IllegalArgumentException("No instruction found for offset: " + offset);

        Label target = new Label(targetIndex, offset);
        labels.put(offset, target);
        return target;
    }

    public FormatFilledArrayData arrayPayload(Format format, int offset) {
        int targetIndex = lookup(format, offset);

        return (FormatFilledArrayData) instructions.get(targetIndex);
    }

    public FormatPackedSwitch packedSwitchPayload(Format format, int offset) {
        int targetIndex = lookup(format, offset);

        return (FormatPackedSwitch) instructions.get(targetIndex);
    }

    public FormatSparseSwitch sparseSwitchPayload(Format format, int offset) {
        int targetIndex = lookup(format, offset);

        return (FormatSparseSwitch) instructions.get(targetIndex);
    }

    private int lookup(Format format, int offset) {
        int thisIndex = instructions.indexOf(format);
        int thisPosition = offsets.get(thisIndex);

        int targetPosition = thisPosition + offset;

        int targetIndex = offsets.indexOf(targetPosition);
        if (targetIndex == -1)
            throw new IllegalArgumentException("No instruction found for offset: " + offset);

        return targetIndex;
    }

}
