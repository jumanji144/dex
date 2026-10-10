package me.darknet.dex.tree.definitions.debug;


import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.debug.*;
import me.darknet.dex.file.instructions.PseudoFormat;
import me.darknet.dex.file.items.DebugInfoItem;
import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.Types;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DebugStateMachine {

    private static final int DBG_FIRST_SPECIAL = 0x0A;
    private static final int DBG_LINE_BASE = -4;
    private static final int DBG_LINE_RANGE = 15;

    private List<DebugInformation.LineNumber> lineNumbers = new ArrayList<>();
    private List<DebugInformation.LocalVariable> locals = new ArrayList<>();
    private InstructionContext<?> ctx;
    private int pc;
    private int currentLine;
    /** Start of the method's last instruction (payloads included). Positions past it are not emitted. */
    private int lastInstruction;
    /** Set once a position past the last instruction is seen; like dexdump, no later positions are emitted. */
    private boolean positionsEnded;

    private Map<Integer, DebugInformation.LocalVariable> activeLocals = new HashMap<>();

    private void execute(DebugInstruction instruction) {
        switch (instruction) {
            case DebugAdvancePc(int addrDiff) -> pc += addrDiff;
            case DebugAdvanceLine(int lineDiff) -> currentLine += lineDiff; // advances the line only; emits no position
            case DebugStartLocal(int registerNum, StringItem name, TypeItem type) -> {
                DebugInformation.LocalVariable local = new DebugInformation.LocalVariable(
                        registerNum,
                        name.string(),
                        Types.typeFromDescriptor(type.descriptor().string()),
                        null,
                        instructionLabel(pc),
                        new Label()
                );
                activeLocals.put(registerNum, local);
            }
            case DebugStartLocalExtended(int registerNum, StringItem name, TypeItem type, StringItem signature) -> {
                DebugInformation.LocalVariable local = new DebugInformation.LocalVariable(
                        registerNum,
                        name.string(),
                        Types.typeFromDescriptor(type.descriptor().string()),
                        signature.string(),
                        instructionLabel(pc),
                        new Label()
                );
                activeLocals.put(registerNum, local);
            }
            case DebugEndLocal(int registerNum) -> {
                DebugInformation.LocalVariable local = activeLocals.remove(registerNum);
                if (local != null) {
                    local.end().position(pc);
                    locals.add(local);
                }
            }
            case DebugRestartLocal(int registerNum) -> {
                // find the last local with this register
                for (int i = locals.size() - 1; i >= 0; i--) {
                    DebugInformation.LocalVariable local = locals.get(i);
                    if (local.register() == registerNum) {
                        DebugInformation.LocalVariable newLocal = new DebugInformation.LocalVariable(
                                local.register(),
                                local.name(),
                                local.type(),
                                local.signature(),
                                instructionLabel(pc),
                                new Label()
                        );
                        activeLocals.put(registerNum, newLocal);
                        break;
                    }
                }
            }
            case DebugSetPrologueEnd ignored -> {
                // Ignored for now
            }
            case DebugSetFile ignored2 -> {
                // Ignored for now
            }
            case DebugSetEpilogueBegin ignored1 -> {
                // Ignored for now
            }
            case DebugSpecial(int opcode) -> {
                int adjustedOpcode = opcode - 0x0A;
                int lineDiff = (adjustedOpcode % 15) - 4;
                int addrDiff = adjustedOpcode / 15;
                pc += addrDiff;
                currentLine += lineDiff;
                readPosition(currentLine);
            }
            default -> throw new IllegalStateException("Unexpected value: " + instruction);
        }
    }

    /**
     * Records a positions entry at the current pc. Mirrors dexdump's position output, from
     * art/dexdump/dexdump.cc (dumpCode, positions block, and findLastInstructionAddress): the first entry past
     * the method's last instruction start ends position output, and entries past it are dropped. Entries
     * inside an instruction keep their line but are attached to that instruction, since our model holds only
     * instruction labels; dexdump prints their raw pc. Dropping and re-attaching are lossy by design, so a
     * round trip of such a method keeps its line table but not its original debug bytes.
     */
    private void readPosition(int line) {
        if (positionsEnded)
            return;
        if (pc > lastInstruction) {
            positionsEnded = true;
            return;
        }
        Label label = instructionLabel(pc);
        label.lineNumber(line);
        lineNumbers.add(new DebugInformation.LineNumber(label, line));
    }

    /**
     * Label for the instruction containing {@code address}. A payload (switch or array data) has an offset but no
     * instruction label: CodeCodec places labels only on real instructions, so an encoder that re-lays out the method
     * would never update a payload label and would write the stale offset. A position inside payload data therefore
     * attaches to the closest real instruction before it.
     */
    private Label instructionLabel(int address) {
        Label label = ctx.labelInexact(address);
        List<?> instructions = ctx.instructions();
        int index = label.index();
        while (index > 0 && instructions.get(index) instanceof PseudoFormat)
            index--;
        return index == label.index() ? label : ctx.labelInexact(ctx.offsets().get(index));
    }

    public DebugInformation execute(DebugInfoItem info, InstructionContext<DexMap> ctx) {
        this.ctx = ctx;
        this.pc = 0;
        this.currentLine = info.lineStart();
        List<Integer> offsets = ctx.offsets();
        this.lastInstruction = offsets.isEmpty() ? -1 : offsets.get(offsets.size() - 1);
        this.positionsEnded = false;

        for (DebugInstruction instruction : info.bytecode()) {
            execute(instruction);
        }

        // insert any still active locals
        for (DebugInformation.LocalVariable local : activeLocals.values()) {
            local.end().position(pc);
            locals.add(local);
        }

        List<String> parameterNames = new ArrayList<>();
        for (StringItem param : info.parameterNames()) {
            parameterNames.add(param.string());
        }

        return new DebugInformation(lineNumbers, parameterNames, locals);
    }

    public DebugInfoItem compile(DebugInformation info, InstructionContext<DexMapBuilder> ctx) {
        int initialLine = info.lineNumbers().isEmpty() ? 0 : info.lineNumbers().getFirst().line();
        this.ctx = ctx;
        this.pc = 0;
        this.currentLine = initialLine;

        List<DebugInstruction> instructions = new ArrayList<>();
        List<DebugEvent> events = new ArrayList<>();

        for (DebugInformation.LineNumber lineNumber : info.lineNumbers()) {
            events.add(DebugEvent.line(lineNumber.label().position(), lineNumber.line()));
        }
        for (DebugInformation.LocalVariable local : info.locals()) {
            events.add(DebugEvent.localEnd(local.end().position(), local));
            events.add(DebugEvent.localStart(local.start().position(), local));
        }

        events.sort(Comparator
                .comparingInt(DebugEvent::pc)
                .thenComparingInt(DebugEvent::priority));

        for (DebugEvent event : events) {
            advancePc(instructions, event.pc());
            switch (event.kind()) {
                case LINE -> emitLine(instructions, event.line());
                case LOCAL_END -> instructions.add(new DebugEndLocal(event.local().register()));
                case LOCAL_START -> emitLocalStart(instructions, ctx, event.local());
            }
        }

        List<StringItem> parameterNames = new ArrayList<>(info.parameterNames().size());
        for (String name : info.parameterNames()) {
            parameterNames.add(name == null ? null : ctx.map().string(name));
        }

        return new DebugInfoItem(initialLine, parameterNames, instructions);
    }

    private void advancePc(List<DebugInstruction> instructions, int targetPc) {
        if (targetPc <= pc)
            return;
        instructions.add(new DebugAdvancePc(targetPc - pc));
        pc = targetPc;
    }

    /**
     * Emits a positions entry at the current pc, which advancePc has already moved to the entry's address.
     * Only special opcodes produce positions; DBG_ADVANCE_LINE does not, so it is used only to reach a line
     * delta that a special opcode cannot encode.
     */
    private void emitLine(List<DebugInstruction> instructions, int targetLine) {
        int lineDiff = targetLine - currentLine;
        currentLine = targetLine;
        if (lineDiff >= DBG_LINE_BASE && lineDiff < DBG_LINE_BASE + DBG_LINE_RANGE) {
            // Address difference is zero, so the opcode encodes only the line difference.
            instructions.add(new DebugSpecial(DBG_FIRST_SPECIAL + (lineDiff - DBG_LINE_BASE)));
        } else {
            instructions.add(new DebugAdvanceLine(lineDiff));
            instructions.add(new DebugSpecial(DBG_FIRST_SPECIAL - DBG_LINE_BASE)); // zero address and line difference
        }
    }

    private void emitLocalStart(List<DebugInstruction> instructions, InstructionContext<DexMapBuilder> ctx,
                                DebugInformation.LocalVariable local) {
        if (local.signature() != null) {
            instructions.add(new DebugStartLocalExtended(
                    local.register(),
                    ctx.map().string(local.name()),
                    ctx.map().type(local.type()),
                    ctx.map().string(local.signature())
            ));
        } else {
            instructions.add(new DebugStartLocal(
                    local.register(),
                    ctx.map().string(local.name()),
                    ctx.map().type(local.type())
            ));
        }
    }

    private record DebugEvent(Kind kind, int pc, int line, DebugInformation.LocalVariable local) {
        static DebugEvent line(int pc, int line) {
            return new DebugEvent(Kind.LINE, pc, line, null);
        }

        static DebugEvent localEnd(int pc, DebugInformation.LocalVariable local) {
            return new DebugEvent(Kind.LOCAL_END, pc, 0, local);
        }

        static DebugEvent localStart(int pc, DebugInformation.LocalVariable local) {
            return new DebugEvent(Kind.LOCAL_START, pc, 0, local);
        }

        int priority() {
            return switch (kind) {
                case LINE -> 0;
                case LOCAL_END -> 1;
                case LOCAL_START -> 2;
            };
        }
    }

    private enum Kind {
        LINE,
        LOCAL_END,
        LOCAL_START
    }

}
