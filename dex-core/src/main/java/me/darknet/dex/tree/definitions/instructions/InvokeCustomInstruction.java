package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBBCCCC;
import me.darknet.dex.file.instructions.FormatAGopBBBBFEDC;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.file.items.CallSiteDataItem;
import me.darknet.dex.file.items.CallSiteItem;
import me.darknet.dex.file.value.Value;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.Handle;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Types;

import java.util.ArrayList;
import java.util.List;

public class InvokeCustomInstruction implements Instruction {

    private final Handle handle;
    private final String name;
    private final MethodType type;
    private final List<Constant> arguments;

    private final int[] argumentRegisters;
    private final int size;
    private final int first;

    public InvokeCustomInstruction(Handle handle, String name, MethodType type, List<Constant> arguments,
                                   int... argumentRegisters) {
        this.handle = handle;
        this.name = name;
        this.type = type;
        this.arguments = arguments;
        this.argumentRegisters = argumentRegisters;
        this.size = argumentRegisters.length;
        this.first = argumentRegisters.length > 0 ? argumentRegisters[0] : -1;

        if (argumentRegisters.length > 5) {
            // make sure they are in sequential order
            for (int i = 1; i < argumentRegisters.length; i++) {
                if (argumentRegisters[i] != argumentRegisters[i - 1] + 1) {
                    throw new IllegalArgumentException("Registers must be in sequential order");
                }
            }
        }
    }

    public InvokeCustomInstruction(Handle handle, String name, MethodType type, List<Constant> arguments,
                                   int size, int first) {
        this.handle = handle;
        this.name = name;
        this.type = type;
        this.arguments = arguments;
        this.argumentRegisters = null;
        this.size = size;
        this.first = first;
    }

    public Handle handle() {
        return handle;
    }

    public String name() {
        return name;
    }

    public MethodType type() {
        return type;
    }

    public List<Constant> arguments() {
        return arguments;
    }

    public int[] argumentRegisters() {
        return argumentRegisters;
    }

    private int size() {
        return size;
    }

    public boolean isRange() {
        return argumentRegisters == null;
    }

    public int first() {
        return first;
    }

    public int last() {
        return argumentRegisters == null ? first + size - 1 : argumentRegisters[size - 1];
    }

    @Override
    public int opcode() {
        return Opcodes.INVOKE_CUSTOM;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("invoke-custom ");

        if (isRange()) {
            sb.append("{");
            sb.append(first).append(" ... ").append(last()).append("}");
        } else {
            sb.append("{");
            for (int i = 0; i < size; i++) {
                if (i != 0) sb.append(", ");
                sb.append("v").append(argumentRegisters[i]);
            }
            sb.append("}");
        }

        sb.append(", ").append(handle).append(", ").append(name).append(", ").append(type);

        sb.append('(');
        for (int i = 0; i < arguments.size(); i++) {
            if (i != 0) sb.append(", ");
            sb.append(arguments.get(i));
        }
        sb.append(')');

        return sb.toString();
    }

    public static InstructionCodec<InvokeCustomInstruction, Format> CODEC = new InstructionCodec<>() {
        @Override
        public InvokeCustomInstruction map(Format input, InstructionContext<DexMap> context) {
            return switch (input) {
                case FormatAGopBBBBFEDC(int op, int a, int b, int c, int d, int e, int f, int g) -> {
                    CallSiteItem callSite = context.map().callSites().get(g);
                    CallSiteDataItem data = callSite.data();
                    Handle handle = Handle.CODEC.map(data.handle().item(), context.map());
                    String name = data.name().string().string();
                    MethodType type = Types.methodType(data.type().protoItem());
                    List<Constant> arguments = new ArrayList<>(data.arguments().size());
                    for (Value value : data.arguments()) {
                        arguments.add(Constant.CODEC.map(value, context.map()));
                    }
                    yield new InvokeCustomInstruction(handle, name, type, arguments, a, b, c, d, e, f);
                }
                case FormatAAopBBBBCCCC(int op, int a, int b, int c) -> {
                    CallSiteItem callSite = context.map().callSites().get(c);
                    CallSiteDataItem data = callSite.data();
                    Handle handle = Handle.CODEC.map(data.handle().item(), context.map());
                    String name = data.name().string().string();
                    MethodType type = Types.methodType(data.type().protoItem());
                    List<Constant> arguments = new ArrayList<>(data.arguments().size());
                    for (Value value : data.arguments()) {
                        arguments.add(Constant.CODEC.map(value, context.map()));
                    }
                    yield new InvokeCustomInstruction(handle, name, type, arguments, a);
                }
                default -> throw new IllegalArgumentException("Invalid format: " + input);
            };
        }

        @Override
        public Format unmap(InvokeCustomInstruction output, InstructionContext<DexMapBuilder> context) {
            int callSiteIndex = context.map().addCallSite(output.handle, output.name, output.type, output.arguments);
            if (output.isRange()) {
                return new FormatAGopBBBBFEDC(Opcodes.INVOKE_CUSTOM_RANGE, output.size(), callSiteIndex,
                        output.first(), output.first() + 1, output.first() + 2, output.first() + 3, output.first() + 4);
            } else {
                return new FormatAAopBBBBCCCC(Opcodes.INVOKE_CUSTOM, output.size(), callSiteIndex, output.first());
            }
        }
    };

    @Override
    public int byteSize() {
        return 3;
    }
}
