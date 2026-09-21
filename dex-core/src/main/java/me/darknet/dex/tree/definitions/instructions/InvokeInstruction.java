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

import java.util.Arrays;
import java.util.Objects;

public final class InvokeInstruction implements Instruction, Invoke {

    /** Distance between the 35c and 3rc forms of every invoke family except {@code invoke-polymorphic}. */
    private static final int RANGE_OFFSET = Opcodes.INVOKE_VIRTUAL_RANGE - Opcodes.INVOKE_VIRTUAL;

    /**
     * Resolves the non-range opcode a range opcode belongs to. {@code invoke-polymorphic} is the odd one out:
     * its range form sits directly behind its 35c form, while the other families leave the unused {@code 0x73}
     * opcode between their two forms. Subtracting the shared offset would turn {@code 0xfb} into {@code 0xf5},
     * which is an entirely different instruction.
     *
     * @param rangeOpcode
     * 		Opcode of a range form invoke.
     *
     * @return Opcode of the matching non-range form.
     */
    private static int baseOpcode(int rangeOpcode) {
        return rangeOpcode == Opcodes.INVOKE_POLYMORPHIC_RANGE
                ? Opcodes.INVOKE_POLYMORPHIC
                : rangeOpcode - RANGE_OFFSET;
    }

    @MagicConstant(intValues = {VIRTUAL, DIRECT, STATIC, INTERFACE, SUPER, POLYMORPHIC})
    private final int kind;
    private final ReferenceType owner;
    private final String name;
    /**
     * Prototype the call is made with. Every constant pool index except {@code proto@HHHH} describes the
     * referenced method, so this only diverges from {@link #methodType} for invoke-polymorphic.
     */
    private final MethodType type;
    /**
     * Prototype the referenced method was declared with. Signature polymorphic methods such as
     * {@code MethodHandle.invoke} are declared with {@code ([Ljava/lang/Object;)Ljava/lang/Object;} while the
     * call site describes the real argument and return types, so both prototypes have to survive a round trip.
     */
    private final MethodType methodType;

    private final int[] arguments;
    private final int size;
    private final int first;

    public InvokeInstruction(int kind, ReferenceType owner, String name, MethodType type, int... arguments) {
        this(kind, owner, name, type, type, arguments);
    }

    private InvokeInstruction(int kind, ReferenceType owner, String name, MethodType type, MethodType methodType,
                              int... arguments) {
        this(kind, owner, name, type, methodType, arguments.length, arguments.length > 0 ? arguments[0] : -1, arguments);

        if (arguments.length > 5) {
            // make sure they are in sequential order
            for (int i = 1; i < arguments.length; i++) {
                if (arguments[i] != arguments[i - 1] + 1) {
                    throw new IllegalArgumentException("Registers must be in sequential order");
                }
            }
        }
    }

    private InvokeInstruction(int kind, ReferenceType owner, String name, MethodType type, MethodType methodType,
                              int size, int first, int[] arguments) {
        this.kind = kind;
        this.owner = owner;
        this.name = name;
        this.type = type;
        this.methodType = methodType;
        this.size = size;
        this.first = first;

        // Explicit int[] arg prevents method signature confusion.
        // Should always be 'null'
        this.arguments = arguments;
    }

    public static @NotNull InvokeInstruction range(int kind, ReferenceType owner, String name, MethodType type, int size, int first) {
        return new InvokeInstruction(baseOpcode(kind), owner, name, type, type, size, first, null);
    }

    /**
     * @param owner
     * 		Type declaring the signature polymorphic method.
     * @param name
     * 		Name of the signature polymorphic method.
     * @param methodType
     * 		Prototype the referenced method was declared with, as encoded by {@code meth@BBBB}.
     * @param type
     * 		Prototype of the actual invocation, as encoded by {@code proto@HHHH}.
     * @param arguments
     * 		Registers holding the arguments, in order.
     *
     * @return An {@code invoke-polymorphic} instruction.
     */
    public static @NotNull InvokeInstruction polymorphic(ReferenceType owner, String name, MethodType methodType,
                                                         MethodType type, int... arguments) {
        return new InvokeInstruction(POLYMORPHIC, owner, name, type, methodType, arguments);
    }

    /**
     * @param owner
     * 		Type declaring the signature polymorphic method.
     * @param name
     * 		Name of the signature polymorphic method.
     * @param methodType
     * 		Prototype the referenced method was declared with, as encoded by {@code meth@BBBB}.
     * @param type
     * 		Prototype of the actual invocation, as encoded by {@code proto@HHHH}.
     * @param size
     * 		Number of argument words this invocation consumes.
     * @param first
     * 		First register of the run holding the arguments.
     *
     * @return An {@code invoke-polymorphic/range} instruction.
     */
    public static @NotNull InvokeInstruction polymorphicRange(ReferenceType owner, String name, MethodType methodType,
                                                             MethodType type, int size, int first) {
        return new InvokeInstruction(POLYMORPHIC, owner, name, type, methodType, size, first, null);
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

    public MethodType methodType() {
        return methodType;
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

    /**
     * @return Opcode this instruction has to be written with, which is the range form when it carries a run
     * 		of registers.
     */
    private int encodedOpcode() {
        if (kind != POLYMORPHIC)
            return isRange() ? kind + RANGE_OFFSET : kind;
        return isRange() ? Opcodes.INVOKE_POLYMORPHIC_RANGE : kind;
    }

    private static String kindToString(int kind) {
        return switch (kind) {
            case VIRTUAL -> "invoke-virtual";
            case DIRECT -> "invoke-direct";
            case STATIC -> "invoke-static";
            case INTERFACE -> "invoke-interface";
            case SUPER -> "invoke-super";
            case POLYMORPHIC -> "invoke-polymorphic";
            default -> throw new IllegalArgumentException("Invalid kind: " + kind);
        };
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof InvokeInstruction that))
            return false;

	    return kind == that.kind
                && size == that.size
                && first == that.first
                && Objects.equals(owner, that.owner)
                && Objects.equals(name, that.name)
                && Objects.equals(type, that.type)
                && Objects.equals(methodType, that.methodType)
                && Arrays.equals(arguments, that.arguments);
    }

    @Override
    public int hashCode() {
        int result = kind;
        result = 31 * result + Objects.hashCode(owner);
        result = 31 * result + Objects.hashCode(name);
        result = 31 * result + Objects.hashCode(type);
        result = 31 * result + Objects.hashCode(methodType);
        result = 31 * result + Arrays.hashCode(arguments);
        result = 31 * result + size;
        result = 31 * result + first;
        return result;
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

        sb.append(", ").append(owner.internalName()).append(".").append(name).append(methodType);

        // Polymorphic calls carry the call site prototype next to the method's own prototype.
        if (kind == POLYMORPHIC)
            sb.append(", ").append(type);

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
                    // The AA operand counts registers and CCCC is the first of the run, so the 3rc layout
                    // always describes a range instruction. Reading it as a register list would move the
                    // first register into the argument list and lose the range flag.
                    MethodItem method = context.map().methods().get(b);
                    ReferenceType owner = Types.referenceType(method.owner());
                    String name = method.name().string();
                    MethodType type = Types.methodType(method.proto());
                    yield range(op, owner, name, type, a, c);
                }
                case FormatAGopBBBBFEDCHHHH(int op, int a, int b, int c, int d, int e, int f, int g, int h) -> {
                    // Only invoke-polymorphic uses the 45cc layout, so the opcode is implied by the format and
                    // the referenced method's own prototype has to be read next to the call site prototype.
                    MethodItem method = context.map().methods().get(b);
                    ProtoItem proto = context.map().protos().get(h);
                    ReferenceType owner = Types.referenceType(method.owner());
                    String name = method.name().string();
                    MethodType type = Types.methodType(proto);
                    MethodType methodType = Types.methodType(method.proto());

                    int[] arguments = new int[a];
                    System.arraycopy(new int[] {c, d, e, f, g}, 0, arguments, 0, a);
                    yield polymorphic(owner, name, methodType, type, arguments);
                }
                case FormatAAopBBBBCCCCHHHH(int op, int a, int b, int c, int h) -> {
                    MethodItem method = context.map().methods().get(b);
                    ProtoItem proto = context.map().protos().get(h);
                    ReferenceType owner = Types.referenceType(method.owner());
                    String name = method.name().string();
                    MethodType type = Types.methodType(proto);
                    MethodType methodType = Types.methodType(method.proto());
                    yield polymorphicRange(owner, name, methodType, type, a, c);
                }
                default -> throw new IllegalArgumentException("Unmappable format: " + input);
            };
        }

        @Override
        public @NotNull Format unmap(@NotNull InvokeInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            int method = context.map().addMethod(output.owner, output.name, output.methodType);
            if (output.kind == POLYMORPHIC) {
                // The call site prototype is a separate pool entry, so the referenced method keeps the
                // prototype it was declared with while the invocation describes the real call.
                int proto = context.map().addProto(output.type);
                if (output.isRange()) {
                    return new FormatAAopBBBBCCCCHHHH(output.encodedOpcode(), output.size, method, output.first, proto);
                }
                int[] arguments = new int[5];
                System.arraycopy(output.arguments, 0, arguments, 0, output.size);
                return new FormatAGopBBBBFEDCHHHH(output.encodedOpcode(), output.size, method,
                        arguments[0], arguments[1], arguments[2], arguments[3], arguments[4], proto);
            }
            if (output.isRange()) {
                return new FormatAAopBBBBCCCC(output.encodedOpcode(), output.size, method, output.first);
            }
            int[] arguments = new int[5];
            System.arraycopy(output.arguments, 0, arguments, 0, output.size);
            return new FormatAGopBBBBFEDC(output.encodedOpcode(), output.size, method,
                    arguments[0], arguments[1], arguments[2], arguments[3], arguments[4]);
        }
    };

    @Override
    public int unitSize() {
        // Both forms of invoke-polymorphic spend an extra code unit on the call site prototype.
        return kind == POLYMORPHIC ? 4 : 3;
    }

}
