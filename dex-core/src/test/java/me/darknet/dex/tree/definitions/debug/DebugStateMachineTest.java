package me.darknet.dex.tree.definitions.debug;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.debug.DebugAdvanceLine;
import me.darknet.dex.file.debug.DebugAdvancePc;
import me.darknet.dex.file.debug.DebugInstruction;
import me.darknet.dex.file.debug.DebugSpecial;
import me.darknet.dex.file.instructions.FormatFilledArrayData;
import me.darknet.dex.file.items.DebugInfoItem;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.instructions.Label;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugStateMachineTest {

    @Test
    void positionsInsideAndPastTheLastInstructionFollowDexdump() {
        // Method with instruction starts [0, 3] (invoke-direct, return-void): the stream advances the
        // address one unit per special opcode, so pcs 1 and 2 fall inside the first instruction and pc 4
        // lies past the last one. dexdump prints pcs 0..3 and stops at the first position past the end.
        List<DebugInstruction> bytecode = new ArrayList<>();
        bytecode.add(new DebugSpecial(0x0e));
        for (int i = 0; i < 23; i++)
            bytecode.add(new DebugSpecial(0x1e));
        DebugInfoItem item = new DebugInfoItem(1, List.of(), bytecode);

        DebugInformation info = execute(item, List.of(0, 3));

        assertEquals(List.of("0:1", "0:2", "0:3", "3:4"), positions(info));
    }

    @Test
    void advanceLineEmitsNoPosition() {
        DebugInfoItem item = new DebugInfoItem(1, List.of(),
                List.of(new DebugAdvanceLine(5), new DebugSpecial(0x0e)));

        DebugInformation info = execute(item, List.of(0, 3));

        assertEquals(List.of("0:6"), positions(info));
    }

    @Test
    void writtenPositionsReadBackIncludingLargeLineJumps() {
        Label first = new Label(0, 0);
        Label second = new Label(1, 3);
        Label third = new Label(2, 8);
        DebugInformation original = new DebugInformation(List.of(
                new DebugInformation.LineNumber(first, 1),
                new DebugInformation.LineNumber(second, 40),
                new DebugInformation.LineNumber(third, 41)
        ), List.of(), List.of());

        DebugInfoItem written = new DebugStateMachine().compile(original,
                new InstructionContext<>(List.of(), List.of(0, 3, 8), null, new HashMap<>(), null, null, null));
        DebugInformation readBack = execute(written, List.of(0, 3, 8));

        assertEquals(positions(original), positions(readBack));
    }

    @Test
    void positionsOfAMethodWithNoInstructionsAreDropped() {
        DebugInfoItem item = new DebugInfoItem(1, List.of(), List.of(new DebugSpecial(0x0e)));
        assertEquals(List.of(), positions(execute(item, List.of())));
    }

    @Test
    void positionInsidePayloadAttachesToThePrecedingInstruction() {
        // Real instruction at 0, a 4-unit payload at 2 (covering 2..5), real instruction at 6. A position at
        // pc 4 is inside the payload; payload offsets have no instruction label, so it attaches to offset 0.
        List<Object> instructions = List.of("insn", new FormatFilledArrayData(1, new byte[] {0}), "insn");
        DebugInfoItem item = new DebugInfoItem(1, List.of(), List.of(
                new DebugSpecial(0x0e),
                new DebugAdvancePc(4),
                new DebugSpecial(0x0e),
                new DebugAdvancePc(2),
                new DebugSpecial(0x0e)));

        DebugInformation info = execute(item, instructions, List.of(0, 2, 6));

        assertEquals(List.of("0:1", "0:1", "6:1"), positions(info));
    }

    /** Real code items keep instructions and offsets parallel, so the test helper does too. */
    private static DebugInformation execute(DebugInfoItem item, List<Integer> offsets) {
        List<String> instructions = new ArrayList<>();
        for (int i = 0; i < offsets.size(); i++)
            instructions.add("insn");
        return execute(item, instructions, offsets);
    }

    private static DebugInformation execute(DebugInfoItem item, List<?> instructions, List<Integer> offsets) {
        InstructionContext<DexMap> ctx =
                new InstructionContext<>(instructions, offsets, null, new HashMap<>(), null, null, null);
        return new DebugStateMachine().execute(item, ctx);
    }

    private static List<String> positions(DebugInformation info) {
        List<String> out = new ArrayList<>();
        for (DebugInformation.LineNumber line : info.lineNumbers())
            out.add(line.label().position() + ":" + line.line());
        return out;
    }
}
