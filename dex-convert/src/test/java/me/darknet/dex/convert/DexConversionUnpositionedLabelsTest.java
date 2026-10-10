package me.darknet.dex.convert;

import me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy;
import me.darknet.dex.convert.util.Decompile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

class DexConversionUnpositionedLabelsTest {
    private static final String OWNER = "test/UnpositionedLabels";

    @Test
    void convertsAssemblerStyleControlFlowBeforeDexEncoding() {
        DexConversionIr irConversion = new DexConversionIr();
        irConversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
        assertEffectCall(irConversion.toJavaClass(classWithUnpositionedLabels()));
        assertEffectCall(new DexConversionSimple().toJavaClass(classWithUnpositionedLabels()));
    }

    private static void assertEffectCall(byte[] bytecode) {
        String decompiled = Decompile.decompile(OWNER, bytecode);
        assertTrue(decompiled.contains("System.gc();"), decompiled);
    }

    private static ClassDefinition classWithUnpositionedLabels() {
        var type = Types.instanceTypeFromInternalName(OWNER);
        ClassDefinition definition = new ClassDefinition(type, Types.OBJECT, ACC_PUBLIC);
        Code code = new Code(1, 0, 2);
        Label entry = new Label();
        Label returnLabel = new Label();
        Label end = new Label();
        code.addInstruction(entry);
        code.addInstruction(new BranchZeroInstruction(0, 1, returnLabel));
        code.addInstruction(new InvokeInstruction(Invoke.STATIC, Types.instanceType(System.class), "gc",
                Types.methodTypeFromDescriptor("()V")));
        code.addInstruction(returnLabel);
        code.addInstruction(new ReturnInstruction());
        code.addInstruction(end);

        MethodMember method = new MethodMember("run", Types.methodTypeFromDescriptor("(Z)V"), ACC_PUBLIC | ACC_STATIC);
        method.setCode(code);
        definition.putMethod(method);
        return definition;
    }
}
