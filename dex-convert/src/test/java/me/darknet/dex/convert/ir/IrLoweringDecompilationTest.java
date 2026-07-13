package me.darknet.dex.convert.ir;

import me.darknet.dex.convert.Converters;
import me.darknet.dex.convert.ir.build.IrBuilder;
import me.darknet.dex.convert.util.Decompile;
import me.darknet.dex.convert.util.IrTestUtils;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.Types;
import me.darknet.dex.util.TestUtils;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrLoweringDecompilationTest {
    @Test
    void independentArithmeticValuesFormOneExpressionTree() {
        MethodMember method = new MethodMember("expression", Types.methodTypeFromDescriptor("(III)I"), Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        method.setCode(IrTestUtils.code(5, 3,
                new BinaryInstruction(me.darknet.dex.file.instructions.Opcodes.ADD_INT, 0, 2, 3),
                new BinaryInstruction(me.darknet.dex.file.instructions.Opcodes.ADD_INT, 1, 3, 4),
                new BinaryInstruction(me.darknet.dex.file.instructions.Opcodes.ADD_INT, 0, 0, 1),
                new ReturnInstruction(0)));
        String decompiled = Decompile.decompile("TestClass", new IrBuilder(method).build());
        assertTrue(decompiled.contains("return"), decompiled);
    }

    @Test
    void nestedOperandStackCarriesPreserveReceiverEvaluation() {
        ClassDefinition definition = TestUtils.getDexDefinition("040-miranda", "Main");
        String decompiled = Decompile.decompile("Main", Converters.IR.toJavaClass(definition));
        assertFalse(decompiled.contains("StringBuilder "), decompiled);
    }

    @Test
    void adjacentConstructedThrowStaysAnExpression() {
        ClassDefinition definition = TestUtils.getDexDefinition("468-checker-bool-simplif-regression", "Main");
        String decompiled = Decompile.decompile("Main", Converters.IR.toJavaClass(definition));
        assertTrue(decompiled.contains("throw new Error"), decompiled);
        assertFalse(decompiled.matches("(?s).*Error \\w+ = new Error.*"), decompiled);
    }

    @Test
    void singleUseInstanceFieldStaysInLoopCondition() {
        ClassDefinition definition = TestUtils.getDexDefinition("004-checker-UnsafeTest18", "Main$8");
        String decompiled = Decompile.decompile("Main$8", Converters.IR.toJavaClass(definition));
        assertTrue(decompiled.contains("while"), decompiled);
    }
}
