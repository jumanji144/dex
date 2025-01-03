package me.darknet.dex.tree.definitions.code;

import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.codec.definition.CodeCodec;
import me.darknet.dex.tree.definitions.instructions.Instruction;

import java.util.ArrayList;
import java.util.List;

public class Code {

    private int in;
    private int out;
    private int registers;
    private List<Instruction> instructions = new ArrayList<>();
    private List<TryCatch> tryCatch = new ArrayList<>();

    public Code(int in, int out, int registers) {
        this.in = in;
        this.out = out;
        this.registers = registers;
    }

    public int in() {
        return in;
    }

    public void in(int in) {
        this.in = in;
    }

    public int out() {
        return out;
    }

    public void out(int out) {
        this.out = out;
    }

    public int registers() {
        return registers;
    }

    public void registers(int registers) {
        this.registers = registers;
    }

    public List<Instruction> instructions() {
        return instructions;
    }

    public void instructions(List<Instruction> instructions) {
        this.instructions = instructions;
    }

    public List<TryCatch> tryCatch() {
        return tryCatch;
    }

    public void tryCatch(List<TryCatch> tryCatch) {
        this.tryCatch = tryCatch;
    }

    public static final TreeCodec<Code, CodeItem> CODEC = new CodeCodec();

}
