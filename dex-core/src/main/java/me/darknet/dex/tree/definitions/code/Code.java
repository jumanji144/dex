package me.darknet.dex.tree.definitions.code;

import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.codec.definition.CodeCodec;
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Code {
    public static final TreeCodec<Code, CodeItem> CODEC = new CodeCodec();

    private final List<Instruction> instructions = new ArrayList<>();
    private final Map<Instruction, Integer> instructionOffsets = new IdentityHashMap<>();
    private final List<TryCatch> tryCatches = new ArrayList<>();
    private DebugInformation debugInfo;
    private int in;
    private int out;
    private int registers;

    public Code(int in, int out, int registers) {
        this.in = in;
        this.out = out;
        this.registers = registers;
    }

    public int getIn() {
        return in;
    }

    public void setIn(int in) {
        this.in = in;
    }

    public int getOut() {
        return out;
    }

    public void setOut(int out) {
        this.out = out;
    }

    public int getRegisters() {
        return registers;
    }

    public void setRegisters(int registers) {
        this.registers = registers;
    }

    public @NotNull List<Instruction> getInstructions() {
        return instructions;
    }

    public void addInstruction(@NotNull Instruction instruction) {
        instructions.add(instruction);
    }

    public void addInstructions(@NotNull List<Instruction> instructions) {
        instructions.forEach(this::addInstruction);
    }

    public void setInstructionOffset(@NotNull Instruction instruction, int offset) {
        instructionOffsets.put(instruction, offset);
    }

    public @Nullable Integer offsetOf(@NotNull Instruction instruction) {
        return instructionOffsets.get(instruction);
    }

    public @NotNull List<TryCatch> tryCatch() {
        return tryCatches;
    }

    public void addTryCatch(@NotNull TryCatch tryCatch) {
        tryCatches.add(tryCatch);
    }

    public @Nullable DebugInformation getDebugInfo() {
        return debugInfo;
    }

    public void setDebugInfo(@NotNull DebugInformation debugInfo) {
        this.debugInfo = debugInfo;
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof Code code))
            return false;

	    return in == code.in
                && out == code.out
                && registers == code.registers
                && instructions.equals(code.instructions)
                // TODO: These are identity maps so they won't be equal... We should probably have a special content comparison here
                //   && instructionOffsets.equals(code.instructionOffsets)
                && tryCatches.equals(code.tryCatches)
                && Objects.equals(debugInfo, code.debugInfo);
    }

    @Override
    public int hashCode() {
        int result = instructions.hashCode();
        result = 31 * result + instructionOffsets.hashCode();
        result = 31 * result + tryCatches.hashCode();
        result = 31 * result + Objects.hashCode(debugInfo);
        result = 31 * result + in;
        result = 31 * result + out;
        result = 31 * result + registers;
        return result;
    }
}
