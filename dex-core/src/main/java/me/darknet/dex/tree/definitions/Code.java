package me.darknet.dex.tree.definitions;

import me.darknet.dex.collections.HashBiMap;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Label;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Code {

    private int in;
    private int out;
    private int registers;
    private List<Instruction> instructions = new ArrayList<>();

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

    public static final TreeCodec<Code, CodeItem> CODEC = new TreeCodec<>() {
        @Override
        public Code map(CodeItem input, DexMap context) {
            Code code = new Code(input.in(), input.out(), input.registers());
            Instruction.Context<DexMap> ctx = new Instruction.Context<>(input, context,
                    new HashMap<>(16), new HashMap<>(16), new HashMap<>(16), new HashMap<>(16));
            for (Format instruction : input.instructions()) {
                code.instructions().add(Instruction.CODEC.map(instruction, ctx));
            }
            return code;
        }

        @Override
        public CodeItem unmap(Code output, DexMapBuilder context) {
            return null;
        }
    };

}
