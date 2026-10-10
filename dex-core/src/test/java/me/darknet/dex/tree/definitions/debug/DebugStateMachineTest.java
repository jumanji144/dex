package me.darknet.dex.tree.definitions.debug;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.debug.DebugAdvanceLine;
import me.darknet.dex.file.debug.DebugAdvancePc;
import me.darknet.dex.file.debug.DebugEndLocal;
import me.darknet.dex.file.debug.DebugInstruction;
import me.darknet.dex.file.debug.DebugRestartLocal;
import me.darknet.dex.file.debug.DebugSpecial;
import me.darknet.dex.file.debug.DebugStartLocal;
import me.darknet.dex.file.instructions.FormatFilledArrayData;
import me.darknet.dex.file.items.DebugInfoItem;
import me.darknet.dex.file.items.StringDataItem;
import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.instructions.Label;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebugStateMachineTest {

    @Test
    void unnamedParametersKeepTheirSlots() throws Exception {
        // line 1, two parameters whose names are NO_INDEX (uleb128p1 0), then DBG_END_SEQUENCE. ART requires the
        // stream's parameter count to match the method signature, so both slots must survive the read.
        DebugInfoItem item = DebugInfoItem.CODEC.read(Input.wrap(new byte[] {1, 2, 0, 0, 0}), null);

        assertEquals(2, item.parameterNames().size());
        assertNull(item.parameterNames().get(0));
        assertNull(item.parameterNames().get(1));
    }

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

        DebugInformation info = execute(item, List.of(0, 3), 4);

        assertEquals(List.of("0:1", "0:2", "0:3", "3:4"), positions(info));
    }

    @Test
    void advanceLineEmitsNoPosition() {
        DebugInfoItem item = new DebugInfoItem(1, List.of(),
                List.of(new DebugAdvanceLine(5), new DebugSpecial(0x0e)));

        DebugInformation info = execute(item, List.of(0, 3), 4);

        assertEquals(List.of("0:6"), positions(info));
    }

    @Test
    void lineAtIgnoresPositionsInsideAnInstruction() {
        // The instruction at 0 is on line 1
        // The positions at pcs 1 and 2 are inside it and do not change its line, which is what ART's GetLineNumForPc returns for pc 0.
        List<DebugInstruction> bytecode = new ArrayList<>();
        bytecode.add(new DebugSpecial(0x0e));
        for (int i = 0; i < 23; i++)
            bytecode.add(new DebugSpecial(0x1e));
        DebugInformation info = execute(new DebugInfoItem(1, List.of(), bytecode), List.of(0, 3), 4);

        assertEquals(1, info.lineAt(0));
        assertEquals(4, info.lineAt(3));
    }

    @Test
    void lineAtIsNullBeforeTheFirstPosition() {
        DebugInfoItem item = new DebugInfoItem(1, List.of(), List.of(new DebugAdvancePc(2), new DebugSpecial(0x0e)));

        DebugInformation info = execute(item, List.of(0, 2), 3);

        assertNull(info.lineAt(0));
        assertEquals(1, info.lineAt(2));
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
        ), List.of(), List.of(), List.of());

        DebugInfoItem written = new DebugStateMachine().compile(original,
                new InstructionContext<>(List.of(), List.of(0, 3, 8), null, new HashMap<>(), null, null, null));
        DebugInformation readBack = execute(written, List.of(0, 3, 8), 9);

        assertEquals(positions(original), positions(readBack));
    }

    @Test
    void positionsOfAMethodWithNoInstructionsAreDropped() {
        DebugInfoItem item = new DebugInfoItem(1, List.of(), List.of(new DebugSpecial(0x0e)));
        assertEquals(List.of(), positions(execute(item, List.of(), 0)));
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

        DebugInformation info = execute(item, instructions, List.of(0, 2, 6), 7);

        assertEquals(List.of("0:1", "0:1", "6:1"), positions(info));
    }

    @Test
    void startOnALiveRegisterClosesThePreviousLocalAtThatAddress() {
        // ART emits the previous local when the register is started again (dex_file-inl.h, DecodeDebugLocalInfo).
        DebugInfoItem item = new DebugInfoItem(1, List.of(), List.of(
                start(0, "a", "I"),
                new DebugAdvancePc(3),
                start(0, "b", "I")));

        DebugInformation info = execute(item, List.of(0, 3, 6), 6);

        assertEquals(List.of("a:0-3", "b:3-6"), locals(info));
    }

    @Test
    void startAtAddressZeroOnALiveRegisterDropsThePreviousLocal() {
        // ART drops the closed range when its end address is 0.
        DebugInfoItem item = new DebugInfoItem(1, List.of(), List.of(start(0, "a", "I"), start(0, "b", "I")));

        DebugInformation info = execute(item, List.of(0, 3, 6), 6);

        assertEquals(List.of("b:0-6"), locals(info));
    }

    @Test
    void restartReusesTheDeclarationOfTheLastEndedLocal() {
        DebugInfoItem item = new DebugInfoItem(1, List.of(), List.of(
                start(1, "x", "I"),
                new DebugAdvancePc(3),
                new DebugEndLocal(1),
                new DebugRestartLocal(1)));

        DebugInformation info = execute(item, List.of(0, 3, 6), 6);

        assertEquals(List.of("x:0-3", "x:3-6"), locals(info));
    }

    @Test
    void localsStillLiveAtTheEndEndAtTheCodeEndInRegisterOrder() {
        DebugInfoItem item = new DebugInfoItem(1, List.of(), List.of(start(2, "c", "I"), start(0, "a", "I")));

        DebugInformation info = execute(item, List.of(0, 3, 6), 6);

        assertEquals(List.of("a:0-6", "c:0-6"), locals(info));
    }

    @Test
    void endInsideAPayloadAttachesToTheNextRealInstruction() {
        // The payload covers 2..5. An end at pc 2 is the payload's start, so the range ends at the next real
        // instruction, offset 6.
        List<Object> instructions = List.of("insn", new FormatFilledArrayData(1, new byte[] {0}), "insn");
        DebugInfoItem item = new DebugInfoItem(1, List.of(), List.of(
                start(0, "a", "I"),
                new DebugAdvancePc(2),
                new DebugEndLocal(0)));

        DebugInformation info = execute(item, instructions, List.of(0, 2, 6), 7);

        assertEquals(List.of("a:0-6"), locals(info));
    }

    private static DebugStartLocal start(int register, String name, String descriptor) {
        return new DebugStartLocal(register,
                new StringItem(new StringDataItem(name)),
                new TypeItem(new StringItem(new StringDataItem(descriptor))));
    }

    /** Real code items keep instructions and offsets parallel, so the test helper does too. */
    private static DebugInformation execute(DebugInfoItem item, List<Integer> offsets, int codeEnd) {
        List<String> instructions = new ArrayList<>();
        for (int i = 0; i < offsets.size(); i++)
            instructions.add("insn");
        return execute(item, instructions, offsets, codeEnd);
    }

    private static DebugInformation execute(DebugInfoItem item, List<?> instructions, List<Integer> offsets,
                                            int codeEnd) {
        InstructionContext<DexMap> ctx =
                new InstructionContext<>(instructions, offsets, null, new HashMap<>(), null, null, null);
        return new DebugStateMachine().execute(item, ctx, codeEnd);
    }

    private static List<String> positions(DebugInformation info) {
        List<String> out = new ArrayList<>();
        for (DebugInformation.LineNumber line : info.lineNumbers())
            out.add(line.label().position() + ":" + line.line());
        return out;
    }

    private static List<String> locals(DebugInformation info) {
        List<String> out = new ArrayList<>();
        for (DebugInformation.LocalVariable local : info.locals())
            out.add(local.name() + ":" + local.start().position() + "-" + local.end().position());
        return out;
    }
}
