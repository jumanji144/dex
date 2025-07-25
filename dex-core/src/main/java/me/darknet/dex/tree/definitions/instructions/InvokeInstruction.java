package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.*;
import me.darknet.dex.file.items.MethodItem;
import me.darknet.dex.file.items.ProtoItem;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.Types;
import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class InvokeInstruction implements Instruction, Invoke {

    private static final int RANGE_OFFSET = Opcodes.INVOKE_VIRTUAL_RANGE - Opcodes.INVOKE_VIRTUAL;

    @MagicConstant(intValues = {VIRTUAL, DIRECT, STATIC, INTERFACE, SUPER, POLYMORPHIC})
    private final int kind;
    private final ReferenceType owner;
    private final String name;
    private final MethodType type;

    private final int[] arguments;
    private final int size;
    private final int first;

    public InvokeInstruction(int kind, ReferenceType owner, String name, MethodType type, int... arguments) {
        this.kind = kind;
        this.owner = owner;
        this.name = name;
        this.type = type;
        this.arguments = arguments;
        this.size = arguments.length;
        this.first = arguments.length > 0 ? arguments[0] : -1;

        if (arguments.length > 5) {
            // make sure they are in sequential order
            for (int i = 1; i < arguments.length; i++) {
                if (arguments[i] != arguments[i - 1] + 1) {
                    throw new IllegalArgumentException("Registers must be in sequential order");
                }
            }
        }
    }

    private InvokeInstruction(int kind, ReferenceType owner, String name, MethodType type, int size, int first, int[] arguments) {
        this.kind = kind;
        this.owner = owner;
        this.name = name;
        this.type = type;
        this.size = size;
        this.first = first;

        // Explicit int[] arg prevents method signature confusion.
        // Should always be 'null'
        this.arguments = arguments;
    }

    public static @NotNull InvokeInstruction range(int kind, ReferenceType owner, String name, MethodType type, int size, int first) {
        return new InvokeInstruction(kind - RANGE_OFFSET, owner, name, type, size, first, null);
    }

    public ReferenceType owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public MethodType type() {
        return type;
    }

    public int @Nullable [] arguments() {
        return arguments;
    }

    public boolean isRange() {
        return arguments == null;
    }

    public int first() {
        return first;
    }

    public int last() {
        return arguments == null ? first + size - 1 : arguments[size - 1];
    }

    @Override
    public int opcode() {
        return kind;
    }

    private static String kindToString(int kind) {
        return switch (kind) {
            case VIRTUAL -> "invoke-virtual";
            case DIRECT -> "invoke-direct";
            case STATIC -> "invoke-static";
            case INTERFACE -> "invoke-interface";
            case SUPER -> "invoke-super";
            default -> throw new IllegalArgumentException("Invalid kind: " + kind);
        };
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(kindToString(kind)).append(" ");

        if (isRange()) {
            sb.append("{");
            sb.append(first).append(" ... ").append(last()).append("}");
        } else {
            sb.append("{");
            for (int i = 0; i < size; i++) {
                if (i != 0) sb.append(", ");
                sb.append("v").append(arguments[i]);
            }
            sb.append("}");
        }

        sb.append(", ").append(owner.internalName()).append(".").append(name).append(type);

        return sb.toString();
    }

    public static final InstructionCodec<InvokeInstruction, Format> CODEC = new InstructionCodec<>() {

        @Override
        public @NotNull InvokeInstruction map(@NotNull Format input, @NotNull InstructionContext<DexMap> context) {
            return switch(input) {
                case FormatAGopBBBBFEDC(int op, int a, int b, int c, int d, int e, int f, int g) -> {
                    MethodItem method = context.map().methods().get(b);
                    ReferenceType owner = Types.referenceType(method.owner());
                    String name = method.name().string();
                    MethodType type = Types.methodType(method.proto());

                    int[] arguments = new int[a];
                    System.arraycopy(new int[] {c, d, e, f, g}, 0, arguments, 0, a);
                    yield new InvokeInstruction(op, owner, name, type, arguments);
                }
                case FormatAAopBBBBCCCC(int op, int a, int b, int c) -> {
                    MethodItem method = context.map().methods().get(b);
                    ReferenceType owner = Types.referenceType(method.owner());
                    String name = method.name().string();
                    MethodType type = Types.methodType(method.proto());
                    if (isRangeOp(op))
                        yield range(op, owner, name, type, a, c);
                    yield new InvokeInstruction(op, owner, name, type, a, c);
                }
                case FormatAGopBBBBFEDCHHHH(int op, int a, int b, int c, int d, int e, int f, int g, int h) -> {
                    MethodItem method = context.map().methods().get(b);
                    ProtoItem proto = context.map().protos().get(h);
                    ReferenceType owner = Types.referenceType(method.owner());
                    String name = method.name().string();
                    MethodType type = Types.methodType(proto);

                    int[] arguments = new int[a];
                    System.arraycopy(new int[] {c, d, e, f, g}, 0, arguments, 0, a);
                    yield new InvokeInstruction(op, owner, name, type, arguments);
                }
                case FormatAAopBBBBCCCCHHHH(int op, int a, int b, int c, int h) -> {
                    MethodItem method = context.map().methods().get(b);
                    ProtoItem proto = context.map().protos().get(h);
                    ReferenceType owner = Types.referenceType(method.owner());
                    String name = method.name().string();
                    MethodType type = Types.methodType(proto);
                    yield new InvokeInstruction(op, owner, name, type, a, c);
                }
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public @NotNull Format unmap(@NotNull InvokeInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int method = context.map().addMethod(output.owner, output.name, output.type);
            if (output.kind == POLYMORPHIC) {
                int proto = context.map().addProto(output.type);
                if (output.isRange()) {
                    return new FormatAAopBBBBCCCCHHHH(output.kind + RANGE_OFFSET, output.size, method, output.first, proto);
                }
                int[] arguments = new int[5];
                System.arraycopy(output.arguments, 0, arguments, 0, output.size);
                return new FormatAGopBBBBFEDCHHHH(output.kind, output.size, method,
                        arguments[0], arguments[1], arguments[2], arguments[3], arguments[4], proto);
            }
            if (output.isRange()) {
                return new FormatAAopBBBBCCCC(output.kind + RANGE_OFFSET, output.size, method, output.first);
            }
            int[] arguments = new int[5];
            System.arraycopy(output.arguments, 0, arguments, 0, output.size);
            return new FormatAGopBBBBFEDC(output.kind, output.size, method,
                    arguments[0], arguments[1], arguments[2], arguments[3], arguments[4]);
        }
    };

    @Override
    public int byteSize() {
        return kind == POLYMORPHIC ? 4 : 3;
    }

    private static boolean isRangeOp(int op) {
        return op >= INVOKE_VIRTUAL_RANGE && op <= INVOKE_INTERFACE_RANGE;
    }

}
