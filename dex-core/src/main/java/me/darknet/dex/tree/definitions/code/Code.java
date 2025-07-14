package me.darknet.dex.tree.definitions.code;

import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.codec.definition.CodeCodec;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class Code {
    public static final TreeCodec<Code, CodeItem> CODEC = new CodeCodec();

    private final List<Instruction> instructions = new ArrayList<>();
    private final List<TryCatch> tryCatches = new ArrayList<>();
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

    public @NotNull List<TryCatch> tryCatch() {
        return tryCatches;
    }

    public void addTryCatch(@NotNull TryCatch tryCatch) {
        tryCatches.add(tryCatch);
    }
}
