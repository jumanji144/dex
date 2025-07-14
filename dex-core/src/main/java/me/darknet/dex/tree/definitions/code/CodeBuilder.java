package me.darknet.dex.tree.definitions.code;

import me.darknet.dex.builder.Builder;
import me.darknet.dex.tree.definitions.instructions.*;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CodeBuilder implements Builder<Code> {

    private final List<Instruction> instructions = new ArrayList<>();
    private int registers;
    private int in;
    private int out;

    public CodeBuilder add(Instruction instruction) {
        instructions.add(instruction);
        return this;
    }

    public CodeBuilder const_string(int register, String value) {
        return add(new ConstStringInstruction(register, value));
    }

    public CodeBuilder static_operation(int kind, int value, InstanceType owner, String name, ClassType type) {
        return add(new StaticFieldInstruction(kind, value, owner, name, type));
    }

    public CodeBuilder invoke(int kind, InstanceType owner, String name, MethodType type, int... arguments) {
        return add(new InvokeInstruction(kind, owner, name, type, arguments));
    }

    public CodeBuilder return_void() {
        return add(new ReturnInstruction());
    }

    public CodeBuilder arguments(int in, int out) {
        this.in = in;
        this.out = out;
        return this;
    }

    public CodeBuilder registers(int registers) {
        this.registers = registers;
        return this;
    }

    @Override
    public @NotNull Code build() {
        Code code = new Code(registers, in, out);
        code.instructions(instructions);
        return code;
    }
}
