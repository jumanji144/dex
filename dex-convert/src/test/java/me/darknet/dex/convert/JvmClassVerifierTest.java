package me.darknet.dex.convert;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;

class JvmClassVerifierTest {
	@Test
	void acceptsLoadableClass() {
		assertDoesNotThrow(() -> JvmClassVerifier.verify(classWith(RETURN)));
	}

	@Test
	void rejectsPrimitiveThrownAsReference() {
		assertThrows(IllegalStateException.class, () -> JvmClassVerifier.verify(classWith(ICONST_0, ATHROW)));
	}

	private static byte[] classWith(int... instructions) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(V1_8, ACC_PUBLIC, "VerifierFixture", null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
		method.visitCode();
		for (int instruction : instructions) method.visitInsn(instruction);
		method.visitMaxs(1, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
}
