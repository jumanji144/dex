package me.darknet.dex.tree.definitions.debug;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.debug.DebugAdvanceLine;
import me.darknet.dex.file.debug.DebugAdvancePc;
import me.darknet.dex.file.debug.DebugEndLocal;
import me.darknet.dex.file.debug.DebugInstruction;
import me.darknet.dex.file.debug.DebugRestartLocal;
import me.darknet.dex.file.debug.DebugSetEpilogueBegin;
import me.darknet.dex.file.debug.DebugSetFile;
import me.darknet.dex.file.debug.DebugSetPrologueEnd;
import me.darknet.dex.file.debug.DebugSpecial;
import me.darknet.dex.file.debug.DebugStartLocal;
import me.darknet.dex.file.debug.DebugStartLocalExtended;
import me.darknet.dex.file.instructions.PseudoFormat;
import me.darknet.dex.file.items.DebugInfoItem;
import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads and writes a method's debug stream. Reading follows {@code art/libdexfile/dex/dex_file-inl.h} and
 * {@code art/dexdump/dexdump.cc}, so the line and local tables match dexdump's output, with these deliberate differences:
 * <ul>
 *   <li>Only explicit {@code DBG_START_LOCAL} entries are modeled. ART also synthesizes entries for {@code this} and
 *       every parameter, which dexdump prints; those are not represented here. A DBG_RESTART_LOCAL on a register
 *       with no explicit start is therefore dropped, since its declaration is not known.</li>
 *   <li>Positions and local boundaries are instruction labels. A raw pc inside an instruction maps to that
 *       instruction (for starts) or to the next real instruction (for ends); a pc inside payload data maps to the
 *       preceding (starts and positions) or next (ends) real instruction. dexdump prints the raw pc.</li>
 * </ul>
 */
public class DebugStateMachine {

    private static final int DBG_FIRST_SPECIAL = 0x0A;
    private static final int DBG_LINE_BASE = -4;
    private static final int DBG_LINE_RANGE = 15;

    private final List<DebugInformation.LineNumber> lineNumbers = new ArrayList<>();
    private final List<DebugInformation.LocalVariable> locals = new ArrayList<>();
    private InstructionContext<?> ctx;
    private int pc;
    private int currentLine;
    /** Start of the method's last instruction (payloads included). Positions past it are not emitted. */
    private int lastInstruction;
    /** Set once a position past the last instruction is seen; like dexdump, no later positions are emitted. */
    private boolean positionsEnded;
    /** Offset just past the method's last real instruction; locals still live at the end of the stream end here. */
    private int codeEnd;
    /** Locals currently live, by register. A TreeMap keeps end-of-sequence output in register order, as ART does. */
    private final Map<Integer, Pending> live = new TreeMap<>();
    /** Name, type and signature last declared for each register; DBG_RESTART_LOCAL reuses them. */
    private final Map<Integer, Pending> declared = new HashMap<>();

    /** A local whose range has started but not yet ended. */
    private record Pending(int register, String name, Type type, String signature, Label start) {}

    private void execute(DebugInstruction instruction) {
        switch (instruction) {
            case DebugAdvancePc(int addrDiff) -> pc += addrDiff;
            case DebugAdvanceLine(int lineDiff) -> currentLine += lineDiff; // advances the line only; emits no position
            case DebugStartLocal(int registerNum, StringItem name, TypeItem type) ->
                    declare(registerNum, name.string(), Types.typeFromDescriptor(type.descriptor().string()), null);
            case DebugStartLocalExtended(int registerNum, StringItem name, TypeItem type, StringItem signature) ->
                    declare(registerNum, name.string(), Types.typeFromDescriptor(type.descriptor().string()),
                            signature.string());
            case DebugEndLocal(int registerNum) -> {
                Pending local = live.remove(registerNum);
                if (local != null)
                    close(local, endLabel(pc));
            }
            case DebugRestartLocal(int registerNum) -> {
                // Like ART, only a register that is not live restarts, with the name and type it last declared.
                Pending last = declared.get(registerNum);
                if (last != null && !live.containsKey(registerNum))
                    live.put(registerNum, new Pending(registerNum, last.name(), last.type(), last.signature(),
                            instructionLabel(pc)));
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
	 * Records a positions entry at the current pc.
	 *
	 * @param line
	 * 		Line number of the local's declaration, or 0 if unknown.
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
     * Label for the instruction containing {@code address}.
     *
     * @param address
     *         A raw pc inside the method, which may be a payload offset.
     *
     * @return The label of the instruction containing {@code address}, or the closest real instruction before it if
     * {@code address} is a payload offset.
     */
    private Label instructionLabel(int address) {
        Label label = ctx.labelInexact(address);
        List<?> instructions = ctx.instructions();
        int index = label.index();
        while (index > 0 && instructions.get(index) instanceof PseudoFormat)
            index--;
        return index == label.index() ? label : ctx.labelInexact(ctx.offsets().get(index));
    }

    /**
     * Starts a local at the current pc.
     * <p>
     * Mirrors {@code DecodeDebugLocalInfo} in {@code art/libdexfile/dex/dex_file-inl.h}.
     * Like ART, a {@code DBG_START_LOCAL} on a register that is already live ends the previous local first.
     *
     * @param register
     *         Register number of the local.
     * @param name
     *         Name of the local.
     * @param type
     *         Type of the local.
     * @param signature
     *         Signature of the local, or null if none.
     */
    private void declare(int register, String name, Type type, String signature) {
        Pending previous = live.remove(register);
        if (previous != null && pc != 0)
            close(previous, endLabel(pc));
        Pending local = new Pending(register, name, type, signature, instructionLabel(pc));
        live.put(register, local);
        declared.put(register, local);
    }

	/**
	 * Closes all locals still live at the end of the stream, which is the end of the code.
	 *
	 * @param local
	 * 		Local variable that is still live at the end of the stream.
	 * @param end
	 * 		Label for the end of the local variable, which is the end of the code.
	 */
	private void close(@NotNull Pending local, Label end) {
		locals.add(new DebugInformation.LocalVariable(local.register(), local.name(), local.type(),
				local.signature(), local.start(), end));
	}

	/**
	 * Label for the instruction containing {@code address}, or the closest real instruction before it if
	 *
	 * @param address
	 * 		A raw pc inside the method, which may be a payload offset.
	 *
	 * @return The label of the instruction containing {@code address},
	 * or the closest real instruction before it if {@code address} is a payload offset.
	 */
	private @NotNull Label endLabel(int address) {
        List<Integer> offsets = ctx.offsets();
        int index = Collections.binarySearch(offsets, address);
        if (index < 0)
            index = -index - 1;
        while (index < offsets.size() && isPseudo(index))
            index++;
        return index < offsets.size() ? ctx.label(offsets.get(index)) : codeEndLabel();
    }

	/**
	 * @return Shared code-end label.
	 */
    private Label codeEndLabel() {
		// CodeCodec places this at the end of the method, so the encoder keeps it current
        return ctx.labels().computeIfAbsent(codeEnd, o -> new Label(ctx.offsets().size(), o));
    }

    /**
     * @param index Instruction index.
     * @return {@code true} if the instruction at {@code index} is a pseudo-instruction, {@code false} otherwise.
     */
    private boolean isPseudo(int index) {
        return ctx.instructions().get(index) instanceof PseudoFormat;
    }

    /**
     * Reads one debug stream for a method. {@code codeEnd} is the offset just past the method's last real instruction
     * (payload data excluded), which is where locals still live at the end of the stream end.
     */
    public DebugInformation execute(DebugInfoItem info, InstructionContext<DexMap> ctx, int codeEnd) {
        this.ctx = ctx;
        this.codeEnd = codeEnd;
        this.pc = 0;
        this.currentLine = info.lineStart();
        List<Integer> offsets = ctx.offsets();
        this.lastInstruction = offsets.isEmpty() ? -1 : offsets.get(offsets.size() - 1);
        this.positionsEnded = false;

        for (DebugInstruction instruction : info.bytecode()) {
            execute(instruction);
        }

        // Like ART, every local still live at DBG_END_SEQUENCE ends at the end of the code, in register order.
        for (Pending local : live.values()) {
            close(local, codeEndLabel());
        }
        live.clear();

        List<String> parameterNames = new ArrayList<>();
        for (StringItem param : info.parameterNames()) {
            parameterNames.add(param == null ? null : param.string());
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
