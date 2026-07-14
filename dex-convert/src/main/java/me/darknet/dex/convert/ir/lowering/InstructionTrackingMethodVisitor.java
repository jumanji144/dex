package me.darknet.dex.convert.ir.lowering;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.IdentityHashMap;
import java.util.Map;

import static org.objectweb.asm.Opcodes.ASM9;

/** Tracks emitted instruction positions so exception ranges can never be empty. */
final class InstructionTrackingMethodVisitor extends MethodVisitor {
	private final Map<Label, Integer> labels = new IdentityHashMap<>();
	private int instructionCount;

	InstructionTrackingMethodVisitor(MethodVisitor delegate) {
		super(ASM9, delegate);
	}

	@Override
	public void visitLabel(Label label) {
		labels.putIfAbsent(label, instructionCount);
		super.visitLabel(label);
	}

	@Override public void visitInsn(int opcode) { instructionCount++; super.visitInsn(opcode); }
	@Override public void visitIntInsn(int opcode, int operand) { instructionCount++; super.visitIntInsn(opcode, operand); }
	@Override public void visitVarInsn(int opcode, int var) { instructionCount++; super.visitVarInsn(opcode, var); }
	@Override public void visitTypeInsn(int opcode, String type) { instructionCount++; super.visitTypeInsn(opcode, type); }
	@Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
		instructionCount++; super.visitFieldInsn(opcode, owner, name, descriptor);
	}
	@Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
		instructionCount++; super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
	}
	@Override public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle,
	                                             Object... bootstrapMethodArguments) {
		instructionCount++; super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
	}
	@Override public void visitJumpInsn(int opcode, Label label) { instructionCount++; super.visitJumpInsn(opcode, label); }
	@Override public void visitLdcInsn(Object value) { instructionCount++; super.visitLdcInsn(value); }
	@Override public void visitIincInsn(int var, int increment) { instructionCount++; super.visitIincInsn(var, increment); }
	@Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
		instructionCount++; super.visitTableSwitchInsn(min, max, dflt, labels);
	}
	@Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
		instructionCount++; super.visitLookupSwitchInsn(dflt, keys, labels);
	}
	@Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
		instructionCount++; super.visitMultiANewArrayInsn(descriptor, numDimensions);
	}

	boolean hasInstructionBetween(Label start, Label end) {
		Integer startPosition = labels.get(start);
		Integer endPosition = labels.get(end);
		return startPosition != null && endPosition != null && endPosition > startPosition;
	}
}
