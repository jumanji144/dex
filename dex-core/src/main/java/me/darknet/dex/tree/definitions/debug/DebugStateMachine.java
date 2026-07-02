package me.darknet.dex.tree.definitions.debug;


import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.debug.*;
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

    private List<DebugInformation.LineNumber> lineNumbers = new ArrayList<>();
    private List<DebugInformation.LocalVariable> locals = new ArrayList<>();
    private InstructionContext<?> ctx;
    private int pc;
    private int currentLine;

    private Map<Integer, DebugInformation.LocalVariable> activeLocals = new HashMap<>();

    private void execute(DebugInstruction instruction) {
        switch (instruction) {
            case DebugAdvancePc(int addrDiff) -> pc += addrDiff;
            case DebugAdvanceLine(int lineDiff) -> {
                currentLine += lineDiff;
                Label label = ctx.label(pc);
                label.lineNumber(currentLine);
                lineNumbers.add(new DebugInformation.LineNumber(label, currentLine));
            }
            case DebugStartLocal(int registerNum, StringItem name, TypeItem type) -> {
                DebugInformation.LocalVariable local = new DebugInformation.LocalVariable(
                        registerNum,
                        name.string(),
                        Types.typeFromDescriptor(type.descriptor().string()),
                        null,
                        ctx.label(pc),
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
                        ctx.label(pc),
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
                                ctx.label(pc),
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
                Label label = ctx.label(pc);
                label.lineNumber(currentLine);
                lineNumbers.add(new DebugInformation.LineNumber(label, currentLine));
            }
            default -> throw new IllegalStateException("Unexpected value: " + instruction);
        }
    }

    public DebugInformation execute(DebugInfoItem info, InstructionContext<DexMap> ctx) {
        this.ctx = ctx;
        this.pc = 0;
        this.currentLine = info.lineStart();

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

    private void emitLine(List<DebugInstruction> instructions, int targetLine) {
        instructions.add(new DebugAdvanceLine(targetLine - currentLine));
        currentLine = targetLine;
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
