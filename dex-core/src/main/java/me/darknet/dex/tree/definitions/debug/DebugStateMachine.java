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
        Map<DebugInformation.LocalVariable, Integer> activeLocals = new HashMap<>();
        for (DebugInformation.LineNumber lineNumber : info.lineNumbers()) {
            while (pc < lineNumber.label().position()) {
                // advance pc
                int addrDiff = lineNumber.label().position() - pc;
                if (addrDiff > 0) {
                    instructions.add(new DebugAdvancePc(addrDiff));
                    pc += addrDiff;
                }
            }
            while (currentLine < lineNumber.line()) {
                // advance line
                int lineDiff = lineNumber.line() - currentLine;
                if (lineDiff > 0) {
                    instructions.add(new DebugAdvanceLine(lineDiff));
                    currentLine += lineDiff;
                }
            }
            while (currentLine > lineNumber.line()) {
                // go back a line
                int lineDiff = currentLine - lineNumber.line();
                if (lineDiff > 0) {
                    instructions.add(new DebugAdvanceLine(-lineDiff));
                    currentLine -= lineDiff;
                }
            }
        }

        for (DebugInformation.LocalVariable local : info.locals()) {
            while (pc < local.start().position()) {
                // advance pc
                int addrDiff = local.start().position() - pc;
                if (addrDiff > 0) {
                    instructions.add(new DebugAdvancePc(addrDiff));
                    pc += addrDiff;
                }
            }
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
            activeLocals.put(local, local.register());
            while (pc < local.end().position()) {
                // advance pc
                int addrDiff = local.end().position() - pc;
                if (addrDiff > 0) {
                    instructions.add(new DebugAdvancePc(addrDiff));
                    pc += addrDiff;
                }
            }
            instructions.add(new DebugEndLocal(local.register()));
            activeLocals.remove(local);
        }

        return new DebugInfoItem(initialLine, new ArrayList<>(), instructions);
    }

}
