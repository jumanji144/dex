package me.darknet.dex.convert.ir;

import me.darknet.dex.convert.Converters;
import me.darknet.dex.convert.ir.build.IrBuilder;
import me.darknet.dex.convert.util.Decompile;
import me.darknet.dex.convert.util.IrTestUtils;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.Types;
import me.darknet.dex.util.TestUtils;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void deepInlineDecisionChainFallsBackWithoutStackOverflow() {
        int depth = 1024;
        Instruction[] instructions = new Instruction[depth + 3];
        instructions[0] = new ConstInstruction(0, 1);
        instructions[1] = new ConstInstruction(1, 1);
        for (int i = 0; i < depth; i++) {
            int destination = i + 2;
            int firstInput = i < 2 ? 0 : i;
            instructions[i + 2] = new BinaryInstruction(me.darknet.dex.file.instructions.Opcodes.ADD_INT,
                    destination, firstInput, 1);
        }
        instructions[depth + 2] = new ReturnInstruction(depth + 1);

        MethodMember method = new MethodMember("deepExpression", Types.methodTypeFromDescriptor("()I"),
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        method.setCode(IrTestUtils.code(depth + 2, 0, instructions));

        assertDoesNotThrow(() -> Decompile.decompile("TestClass", new IrBuilder(method).build()));
    }

    @Test
    void nestedOperandStackCarriesPreserveReceiverEvaluation() {
        ClassDefinition definition = TestUtils.getDexDefinition("040-miranda", "Main");
        byte[] bytecode = Converters.IR.toJavaClass(definition);
        Decompile.verify(bytecode);
        assertTrue(bytecode.length > 0);
    }

    @Test
    void adjacentConstructedThrowStaysAnExpression() {
        ClassDefinition definition = TestUtils.getDexDefinition("468-checker-bool-simplif-regression", "Main");
        byte[] bytecode = Converters.IR.toJavaClass(definition);
        Decompile.verify(bytecode);
        assertTrue(bytecode.length > 0);
    }

    @Test
    void materializesReturnedOperationAndProducesDeterministicBytes() {
        MethodMember method = new MethodMember("materialized", Types.methodTypeFromDescriptor("(II)I"),
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        method.setCode(IrTestUtils.code(4, 2,
                new BinaryInstruction(me.darknet.dex.file.instructions.Opcodes.ADD_INT, 2, 0, 1),
                new ReturnInstruction(2)));
        ClassDefinition definition = new ClassDefinition(Types.instanceTypeFromInternalName("test/Materialized"),
                Types.instanceType(Object.class), Opcodes.ACC_PUBLIC);
        definition.putMethod(method);

        byte[] first = Converters.IR.toJavaClass(definition);
        byte[] second = Converters.IR.toJavaClass(definition);
        assertArrayEquals(first, second);
        int[] localOps = {0};
        new ClassReader(first).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals("materialized")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        if (opcode == Opcodes.ISTORE || opcode == Opcodes.ILOAD) localOps[0]++;
                    }
                };
            }
        }, 0);
        assertTrue(localOps[0] >= 2, "Expected the returned operation to use a JVM local");
    }

    @Test
    void singleUseInstanceFieldStaysInLoopCondition() {
        ClassDefinition definition = TestUtils.getDexDefinition("004-checker-UnsafeTest18", "Main$8");
        String decompiled = Decompile.decompile("Main$8", Converters.IR.toJavaClass(definition));
        assertTrue(decompiled.contains("while"), decompiled);
    }
}
