package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBBCCCC;
import me.darknet.dex.file.instructions.FormatAGopBBBBFEDC;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.constant.Handle;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import static me.darknet.dex.file.instructions.Opcodes.INVOKE_CUSTOM;
import static me.darknet.dex.file.instructions.Opcodes.INVOKE_CUSTOM_RANGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests for {@link InvokeCustomInstruction}
 */
class InvokeCustomInstructionTest {

    private static final InstanceType BOOTSTRAP_OWNER = Types.instanceTypeFromInternalName("test/Bootstrap");
    private static final MethodType BOOTSTRAP_TYPE = Types.methodTypeFromDescriptor("(Ltest/Bootstrap;)V");
    private static final MethodType CALL_SITE_TYPE = Types.methodTypeFromDescriptor("(II)I");

    @Test
    void listFormRoundTripsThrough35c() throws IOException {
        Contexts contexts = new Contexts();
        // The filler call site pushes this instruction's index to 1, so reading the index from a register
        // nibble instead of BBBB becomes visible rather than silently resolving to the filler.
        emit(custom("filler"), contexts);

        InvokeCustomInstruction instruction = new InvokeCustomInstruction(handle("dynamic"), "dynamic",
                CALL_SITE_TYPE, List.of(new IntConstant(7)), 2, 3, 4, 5, 6);

        Format format = emit(instruction, contexts);

        assertInstanceOf(FormatAGopBBBBFEDC.class, format, "invoke-custom is a 35c instruction");
        assertEquals(INVOKE_CUSTOM, format.op(), "invoke-custom keeps opcode 0xfc");
        assertEquals(instruction, parse(format, contexts));
    }

    @Test
    void rangeFormRoundTripsThrough3rc() throws IOException {
        Contexts contexts = new Contexts();
        emit(custom("filler"), contexts);

        // Two arguments in three contiguous registers is what invoke-custom/range exists for.
        InvokeCustomInstruction instruction = new InvokeCustomInstruction(handle("dynamic"), "dynamic",
                CALL_SITE_TYPE, List.of(new IntConstant(7)), 3, 8);

        Format format = emit(instruction, contexts);

        assertInstanceOf(FormatAAopBBBBCCCC.class, format, "invoke-custom/range is a 3rc instruction");
        assertEquals(INVOKE_CUSTOM_RANGE, format.op(), "invoke-custom/range keeps opcode 0xfd");
        assertEquals(instruction, parse(format, contexts));
    }

    /**
     * Writes an instruction out to code units and reads the format back, mimicking how a code item is emitted.
     *
     * @param instruction
     * 		Instruction to encode.
     * @param contexts
     * 		Pools to register the call site in and to resolve it from.
     *
     * @return The format decoded back from the written code units.
     *
     * @throws IOException
     * 		If the format cannot be written or decoded.
     */
    private static Format emit(InvokeCustomInstruction instruction, Contexts contexts) throws IOException {
        Format format = Instruction.CODEC.unmap(instruction, contexts.unmap);
        Output output = Output.wrap();
        // Writing picks the codec from the format's opcode, so a format built for the other invoke-custom
        // opcode fails its cast to the codec's own record type right here.
        Format.CODEC.write(format, output);
        return Format.CODEC.read(Input.wrap(output.buffer()));
    }

    /**
     * @param format
     * 		Format decoded from code units.
     * @param contexts
     * 		Pools to resolve the call site from.
     *
     * @return The instruction the format describes.
     */
    private static InvokeCustomInstruction parse(Format format, Contexts contexts) {
        return (InvokeCustomInstruction) Instruction.CODEC.map(format, contexts.map());
    }

    private static InvokeCustomInstruction custom(String name) {
        return new InvokeCustomInstruction(handle(name), name, CALL_SITE_TYPE, List.of());
    }

    private static Handle handle(String name) {
        return new Handle(Handle.KIND_INVOKE_STATIC, BOOTSTRAP_OWNER, name, BOOTSTRAP_TYPE);
    }

    /**
     * Pools a call site is registered in while writing and resolved from while reading.
     */
    private static final class Contexts {

        private final DexMapBuilder builder = new DexMapBuilder();
        private final InstructionContext<DexMapBuilder> unmap;

        private Contexts() {
            this.unmap = new InstructionContext<>(List.of(), List.of(), builder, new HashMap<>(), null, null, null);
        }

        // The dex map is built late so it observes every call site registered while the instruction was written.
        private InstructionContext<DexMap> map() {
            return new InstructionContext<>(List.of(), List.of(), builder.build(), new HashMap<>(), null, null, null);
        }
    }

}
