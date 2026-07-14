package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * JVM value-materialization primitives shared by operation and effect emitters.
 */
final class IrValueEmitter {
	private IrValueEmitter() {}

	static int parameterSlots(@NotNull MethodMember method) {
		int slots = (method.getAccess() & ACC_STATIC) == 0 ? 1 : 0;
		for (ClassType parameterType : method.getType().parameterTypes())
			slots += ConversionSupport.slotSize(parameterType);
		return slots;
	}

	static void pushConstant(@NotNull MethodVisitor mv, @NotNull IrConstant constant,
	                        @NotNull ClassType expectedType) {
		Object value = constant.constantValue();
		if (ConversionSupport.isReferenceType(expectedType)
				&& value != null && !(value instanceof String) && !(value instanceof org.objectweb.asm.Type)
				&& !ConversionSupport.isReferenceType(constant.type())) {
			mv.visitInsn(ACONST_NULL);
			return;
		}
		if (constant.isZeroConstant() && ConversionSupport.isReferenceType(expectedType)) {
			mv.visitInsn(ACONST_NULL);
			return;
		}
		switch (value) {
			case null -> {
				mv.visitInsn(ACONST_NULL);
				return;
			}
			case Integer integer -> {
				if (ConversionSupport.isFloatType(expectedType)) {
					mv.visitLdcInsn(Float.intBitsToFloat(integer));
					return;
				}
				ConversionSupport.pushInt(mv, integer);
				return;
			}
			case Long longValue -> {
				if (ConversionSupport.isDoubleType(expectedType)) {
					mv.visitLdcInsn(Double.longBitsToDouble(longValue));
					return;
				}
				ConversionSupport.pushLong(mv, longValue);
				return;
			}
			default -> {
			}
		}
		mv.visitLdcInsn(value);
	}

	static void store(@NotNull MethodVisitor mv, @NotNull me.darknet.dex.convert.ir.value.IrValue value) {
		mv.visitVarInsn(storeOpcode(value.type()), value.local());
	}

	static void popValue(@NotNull MethodVisitor mv, @NotNull ClassType type) {
		mv.visitInsn(ConversionSupport.isWideType(type) ? POP2 : POP);
	}

	static int loadOpcode(@NotNull ClassType type) {
		if (ConversionSupport.isReferenceType(type)) return ALOAD;
		if (ConversionSupport.isLongType(type)) return LLOAD;
		if (ConversionSupport.isDoubleType(type)) return DLOAD;
		if (ConversionSupport.isFloatType(type)) return FLOAD;
		return ILOAD;
	}

	static int storeOpcode(@NotNull ClassType type) {
		if (ConversionSupport.isReferenceType(type)) return ASTORE;
		if (ConversionSupport.isLongType(type)) return LSTORE;
		if (ConversionSupport.isDoubleType(type)) return DSTORE;
		if (ConversionSupport.isFloatType(type)) return FSTORE;
		return ISTORE;
	}

	static void pushFilledArrayElement(@NotNull MethodVisitor mv, @NotNull ClassType elementType,
	                                   byte[] data, int width, int index) {
		int offset = index * width;
		switch (elementType.descriptor()) {
			case "Z" -> ConversionSupport.pushInt(mv, data[offset] != 0 ? 1 : 0);
			case "B" -> ConversionSupport.pushInt(mv, data[offset]);
			case "C" -> ConversionSupport.pushInt(mv, readUnsignedShort(data, offset));
			case "S" -> ConversionSupport.pushInt(mv, (short) readUnsignedShort(data, offset));
			case "F" -> mv.visitLdcInsn(Float.intBitsToFloat(readInt(data, offset)));
			case "J" -> ConversionSupport.pushLong(mv, readLong(data, offset));
			case "D" -> mv.visitLdcInsn(Double.longBitsToDouble(readLong(data, offset)));
			default -> ConversionSupport.pushInt(mv, readInt(data, offset));
		}
	}

	private static int readUnsignedShort(byte[] data, int offset) {
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
	}

	private static int readInt(byte[] data, int offset) {
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
				| ((data[offset + 2] & 0xFF) << 16) | (data[offset + 3] << 24);
	}

	private static long readLong(byte[] data, int offset) {
		long low = Integer.toUnsignedLong(readInt(data, offset));
		long high = Integer.toUnsignedLong(readInt(data, offset + 4));
		return low | (high << 32);
	}
}
