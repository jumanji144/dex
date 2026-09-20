package me.darknet.dex;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.CodeBuilder;
import me.darknet.dex.tree.definitions.constant.Handle;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for reading a dex back when it contains call sites.
 */
class CallSiteRoundTripTest {

    private static final InstanceType BOOTSTRAP_OWNER =
            Types.instanceTypeFromInternalName("java/lang/invoke/ConstantBootstraps");
    private static final MethodType BOOTSTRAP_TYPE = Types.methodTypeFromDescriptor(
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;");
    private static final MethodType CALL_SITE_TYPE = Types.methodTypeFromDescriptor("()V");

    @Test
    void invokeCustomSurvivesHeaderRoundTrip() throws IOException {
        // Two distinct bootstrap handles, so a pool filled with the wrong entries cannot pass by accident.
        List<InvokeCustomInstruction> instructions = List.of(custom("nullConstant"), custom("staticFieldVarHandle"));

        DexHeader header = DexFile.CODEC.unmap(new DexFile(39, List.of(classWith(instructions))),
                new DexMapBuilder());
        Output output = Output.wrap();
        DexHeader.CODEC.write(header, output);

        // The writer emits call_site_id_item before method_handle_item because the map list has to be ordered
        // by section offset, so a call site always reads its method handle pool before the pool is filled.
        DexHeader roundTripped = DexHeader.CODEC.read(Input.wrap(output.buffer()));

        assertEquals(2, roundTripped.map().methodHandles().size());
        assertEquals(2, roundTripped.map().callSites().size());
        for (int i = 0; i < 2; i++) {
            assertEquals(roundTripped.map().methodHandles().get(i), roundTripped.map().callSites().get(i).data().handle().item(),
                    "call site " + i + " should reference the method handle pool entry with its index");
        }

        DexFile roundTrippedFile = DexFile.CODEC.map(roundTripped, roundTripped.map());
        Code code = roundTrippedFile.definitions().get(0).getMethod("custom", "()V").getCode();
        // Reading inserts labels around branch targets, so compare the call sites rather than the raw list.
        List<Instruction> callSites = code.getInstructions().stream()
                .filter(InvokeCustomInstruction.class::isInstance)
                .toList();
        assertEquals(instructions, callSites,
                "both bootstrap handles should resolve back to the handles they were written from");
        assertTrue(code.getInstructions().contains(new ReturnInstruction()));
    }

    /**
     * @param instructions
     * 		Call site instructions to place in the body of the returned class, in order.
     *
     * @return A class defining {@code Example.custom()V} running the given instructions, then returning.
     */
    private static ClassDefinition classWith(List<InvokeCustomInstruction> instructions) {
        CodeBuilder code = new CodeBuilder();
        for (InvokeCustomInstruction instruction : instructions) {
            // The range form keeps one register, which is all these call sites take.
            code.add(instruction);
        }
        code.add(new ReturnInstruction());

        MethodMember method = new MethodMember("custom", CALL_SITE_TYPE, 0x0009);
        method.setCode(code.arguments(0, 0).registers(1).build());

        ClassDefinition definition = new ClassDefinition(Types.instanceTypeFromInternalName("Example"),
                Types.instanceTypeFromInternalName("java/lang/Object"), 0x0001);
        definition.putMethod(method);
        return definition;
    }

    private static InvokeCustomInstruction custom(String bootstrapName) {
        Handle bootstrap = new Handle(Handle.KIND_INVOKE_STATIC, BOOTSTRAP_OWNER, bootstrapName, BOOTSTRAP_TYPE);
        return new InvokeCustomInstruction(bootstrap, "callsite", CALL_SITE_TYPE, List.of(), 1, 0);
    }

}
