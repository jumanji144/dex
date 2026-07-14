package me.darknet.dex.convert;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JvmMethodShapeValidatorTest {
    @Test
    void rejectsBranchesToSkippedLabels() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, new LabelNode()));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        JvmMethodShapeValidator.Validation validation =
                JvmMethodShapeValidator.validate("test/Owner", method);

        assertFalse(validation.valid());
        assertTrue(validation.reason().contains("branch target label"));
    }

    @Test
    void acceptsAnEmittedBranchTarget() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode target = new LabelNode();
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, target));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(target);
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        JvmMethodShapeValidator.Validation validation =
                JvmMethodShapeValidator.validate("test/Owner", method);
        assertTrue(validation.valid(), validation.reason());
    }

    @Test
    void ignoresBackwardTransferFromExceptionOnlyBridge() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode target = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(target);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.instructions.add(end);
        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, target));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        method.maxStack = 1;
        method.maxLocals = 2;

        JvmMethodShapeValidator.Validation validation =
                JvmMethodShapeValidator.validate("test/Owner", method);
        assertTrue(validation.valid(), validation.reason());
    }
}
