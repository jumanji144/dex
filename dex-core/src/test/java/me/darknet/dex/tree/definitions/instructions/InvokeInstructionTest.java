package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.code.TryItem;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBBCCCCHHHH;
import me.darknet.dex.file.instructions.FormatAGopBBBBFEDCHHHH;
import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import static me.darknet.dex.file.instructions.Opcodes.INVOKE_POLYMORPHIC;
import static me.darknet.dex.file.instructions.Opcodes.INVOKE_POLYMORPHIC_RANGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link InvokeInstruction}, covering the invoke-polymorphic forms which carry a second prototype.
 */
class InvokeInstructionTest {

    private static final InstanceType METHOD_HANDLE =
            Types.instanceTypeFromInternalName("java/lang/invoke/MethodHandle");
    /** Prototype the signature polymorphic methods are declared with. */
    private static final MethodType DECLARED_TYPE =
            Types.methodTypeFromDescriptor("([Ljava/lang/Object;)Ljava/lang/Object;");
    /** Prototype a call site actually invokes them with. */
    private static final MethodType CALL_SITE_TYPE = Types.methodTypeFromDescriptor("(II)I");

    @Test
    void rangeFormKeepsItsOwnOpcodeAndSize() throws IOException {
        Contexts contexts = new Contexts();

        InvokeInstruction instruction = InvokeInstruction.polymorphicRange(METHOD_HANDLE, "invokeExact",
                DECLARED_TYPE, CALL_SITE_TYPE, 2, 3);

        // The range form sits one opcode behind its 35c form, not six like the other invoke families, so it
        // must not be folded onto whichever instruction sits at the shared offset.
        assertEquals(INVOKE_POLYMORPHIC, instruction.opcode());
        assertTrue(instruction.isRange());
        assertEquals(3, instruction.first());
        assertEquals(4, instruction.last(), "two argument words run from v3 to v4");
        assertEquals(4, instruction.unitSize(), "4rcc spends four code units on the call site prototype");

        Format format = emit(instruction, contexts);

        assertInstanceOf(FormatAAopBBBBCCCCHHHH.class, format, "invoke-polymorphic/range is a 4rcc instruction");
        assertEquals(INVOKE_POLYMORPHIC_RANGE, format.op(), "invoke-polymorphic/range keeps opcode 0xfb");
        assertEquals(instruction, parse(format, contexts));
    }

    @Test
    void listFormUsesThe45ccLayout() throws IOException {
        Contexts contexts = new Contexts();

        InvokeInstruction instruction = InvokeInstruction.polymorphic(METHOD_HANDLE, "invokeExact",
                DECLARED_TYPE, CALL_SITE_TYPE, 1, 2);

        assertEquals(INVOKE_POLYMORPHIC, instruction.opcode());
        assertEquals(4, instruction.unitSize(), "45cc spends four code units on the call site prototype");

        Format format = emit(instruction, contexts);

        assertInstanceOf(FormatAGopBBBBFEDCHHHH.class, format, "invoke-polymorphic is a 45cc instruction");
        assertEquals(INVOKE_POLYMORPHIC, format.op(), "invoke-polymorphic keeps opcode 0xfa");
        assertEquals(instruction, parse(format, contexts));
    }

    @Test
    void keepsMethodPrototypeSeparateFromCallSitePrototype() throws IOException {
        Contexts contexts = new Contexts();

        Format format = emit(InvokeInstruction.polymorphicRange(METHOD_HANDLE, "invokeExact",
                DECLARED_TYPE, CALL_SITE_TYPE, 2, 0), contexts);

        // The two pool indices are independent: a written instruction that reuses one prototype for both
        // would leave the referenced method claiming the call site's signature.
        FormatAAopBBBBCCCCHHHH range = assertInstanceOf(FormatAAopBBBBCCCCHHHH.class, format);
        DexMap map = contexts.dexMap();
        assertEquals(DECLARED_TYPE, Types.methodType(map.methods().get(range.b()).proto()),
                "meth@BBBB keeps the prototype the referenced method was declared with");
        assertEquals(CALL_SITE_TYPE, Types.methodType(map.protos().get(range.h())),
                "proto@HHHH holds the prototype of the invocation");
    }

    @Test
    void codeLayoutChargesFourCodeUnitsForPolymorphicInvokes() throws IOException {
        Label start = new Label();
        Label handler = new Label();

        Code code = new Code(0, 2, 3);
        code.addInstruction(start);
        code.addInstruction(InvokeInstruction.polymorphicRange(METHOD_HANDLE, "invokeExact",
                DECLARED_TYPE, CALL_SITE_TYPE, 2, 0));
        code.addInstruction(handler);
        code.addInstruction(new ReturnInstruction());
        code.addTryCatch(new TryCatch(start, handler, List.of(new Handler(handler, null))));

        DexHeader header = DexFile.CODEC.unmap(new DexFile(39, List.of(classWith(code))), new DexMapBuilder());
        CodeItem item = header.map().codes().get(0);

        // A try range is written in absolute code units, so it only reaches past the invoke if the layout
        // charged the range form its four units. A three unit call would leave the range covering half of it.
        TryItem tryItem = item.tries().getFirst();
        assertEquals(0, tryItem.startAddr());
        assertEquals(4, tryItem.count(), "the try range should cover the whole invoke-polymorphic/range");

        // The written file must also read back with both prototypes intact.
        Output output = Output.wrap();
        DexHeader.CODEC.write(header, output);
        DexHeader roundTripped = DexHeader.CODEC.read(Input.wrap(output.buffer()));

        DexFile file = DexFile.CODEC.map(roundTripped, roundTripped.map());
        Code roundTrippedCode = file.definitions().getFirst().getMethod("run", "(II)I").getCode();
        InvokeInstruction invoke = roundTrippedCode.getInstructions().stream()
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(invoke.isRange());
        // Offsets for every following instruction are derived from unitSize, so a decoded 0xfb has to report
        // the four code units its 4rcc encoding actually occupies.
        assertEquals(INVOKE_POLYMORPHIC, invoke.opcode(), "decoding 0xfb must not shift the opcode");
        assertEquals(4, invoke.unitSize());
        assertEquals(0, invoke.first());
        assertEquals(1, invoke.last());
        assertEquals(DECLARED_TYPE, invoke.methodType());
        assertEquals(CALL_SITE_TYPE, invoke.type());
    }

    /**
     * Writes an instruction out to code units and reads the format back, mimicking how a code item is emitted.
     *
     * @param instruction
     * 		Instruction to encode.
     * @param contexts
     * 		Pools to register the method and prototype in and to resolve them from.
     *
     * @return The format decoded back from the written code units.
     *
     * @throws IOException
     * 		If the format cannot be written or decoded.
     */
    private static Format emit(InvokeInstruction instruction, Contexts contexts) throws IOException {
        Format format = Instruction.CODEC.unmap(instruction, contexts.unmap);
        Output output = Output.wrap();
        Format.CODEC.write(format, output);
        return Format.CODEC.read(Input.wrap(output.buffer()));
    }

    /**
     * @param format
     * 		Format decoded from code units.
     * @param contexts
     * 		Pools to resolve the method and prototype from.
     *
     * @return The instruction the format describes.
     */
    private static InvokeInstruction parse(Format format, Contexts contexts) {
        return (InvokeInstruction) Instruction.CODEC.map(format, contexts.map());
    }

    /**
     * @param code
     * 		Body to place in the returned class.
     *
     * @return A class defining {@code Example.run(II)I} with the given body.
     */
    private static ClassDefinition classWith(Code code) {
        MethodMember method = new MethodMember("run", Types.methodTypeFromDescriptor("(II)I"), 0x0009);
        method.setCode(code);

        ClassDefinition definition = new ClassDefinition(Types.instanceTypeFromInternalName("Example"),
                Types.OBJECT, 0x0001);
        definition.putMethod(method);
        return definition;
    }

    /**
     * Pools the method and prototype are registered in while writing and resolved from while reading.
     */
    private static final class Contexts {

        private final DexMapBuilder builder = new DexMapBuilder();
        private final InstructionContext<DexMapBuilder> unmap;

        private Contexts() {
            this.unmap = new InstructionContext<>(List.of(), List.of(), builder, new HashMap<>(), null, null, null);
        }

        // The dex map is built late so it observes every entry registered while the instruction was written.
        private InstructionContext<DexMap> map() {
            return new InstructionContext<>(List.of(), List.of(), dexMap(), new HashMap<>(), null, null, null);
        }

        private DexMap dexMap() {
            return builder.build();
        }
    }

}
