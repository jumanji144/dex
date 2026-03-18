package me.darknet.dex.convert;

import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.constant.AnnotationConstant;
import me.darknet.dex.tree.definitions.constant.ArrayConstant;
import me.darknet.dex.tree.definitions.constant.BoolConstant;
import me.darknet.dex.tree.definitions.constant.ByteConstant;
import me.darknet.dex.tree.definitions.constant.CharConstant;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.DoubleConstant;
import me.darknet.dex.tree.definitions.constant.EnumConstant;
import me.darknet.dex.tree.definitions.constant.FloatConstant;
import me.darknet.dex.tree.definitions.constant.Handle;
import me.darknet.dex.tree.definitions.constant.HandleConstant;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.LongConstant;
import me.darknet.dex.tree.definitions.constant.MemberConstant;
import me.darknet.dex.tree.definitions.constant.NullConstant;
import me.darknet.dex.tree.definitions.constant.ShortConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.definitions.constant.TypeConstant;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.PrimitiveKind;
import me.darknet.dex.tree.type.PrimitiveType;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.Type;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.MethodVisitor;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Utility methods for converting between Dex and Java representations.
 */
public final class ConversionSupport {
	private ConversionSupport() {
	}

	/**
	 * @param av
	 * 		Writing visitor.
	 * @param annotation
	 * 		Annotation to write.
	 */
	public static void visitAnnotation(@NotNull AnnotationVisitor av, @NotNull Annotation annotation) {
		AnnotationPart part = annotation.annotation();
		part.elements().forEach((key, value) -> visitAnnotationElement(av, key, value));
	}

	/**
	 * @param av
	 * 		Writing visitor.
	 * @param key
	 * 		Element name.
	 * @param value
	 * 		Element value.
	 */
	public static void visitAnnotationElement(@NotNull AnnotationVisitor av, @NotNull String key, @NotNull Constant value) {
		switch (value) {
			case BoolConstant constant -> av.visit(key, constant.value());
			case ByteConstant constant -> av.visit(key, constant.value());
			case CharConstant constant -> av.visit(key, constant.value());
			case DoubleConstant constant -> av.visit(key, constant.value());
			case FloatConstant constant -> av.visit(key, constant.value());
			case IntConstant constant -> av.visit(key, constant.value());
			case LongConstant constant -> av.visit(key, constant.value());
			case ShortConstant constant -> av.visit(key, constant.value());
			case StringConstant constant -> av.visit(key, constant.value());
			case EnumConstant constant -> av.visitEnum(key, constant.field().descriptor(), constant.field().name());
			case TypeConstant constant -> av.visit(key, asmType(constant.type()));
			case AnnotationConstant constant -> {
				AnnotationVisitor child = av.visitAnnotation(key, constant.annotation().type().descriptor());
				constant.annotation().elements().forEach((k, v) -> visitAnnotationElement(child, k, v));
			}
			case ArrayConstant constant -> {
				AnnotationVisitor child = av.visitArray(key);
				for (Constant element : constant.constants()) {
					visitAnnotationElement(child, "", element);
				}
				child.visitEnd();
			}
			// These constants are not valid annotation element values, so we skip them.
			case HandleConstant ignored -> {}
			case MemberConstant ignored -> {}
			case NullConstant ignored -> {}
		}
	}

	/**
	 * @param value
	 * 		Constant to map.
	 *
	 * @return Mapped constant value, or {@code null} if the input is null or a {@link NullConstant}.
	 */
	public static @Nullable Object mapConstant(@Nullable Constant value) {
		if (value == null) return null;
		return switch (value) {
			case BoolConstant constant -> constant.value();
			case ByteConstant constant -> constant.value();
			case CharConstant constant -> constant.value();
			case DoubleConstant constant -> constant.value();
			case FloatConstant constant -> constant.value();
			case IntConstant constant -> constant.value();
			case LongConstant constant -> constant.value();
			case ShortConstant constant -> constant.value();
			case StringConstant constant -> constant.value();
			case TypeConstant constant -> asmType(constant.type());
			case NullConstant ignored -> null;
			default -> throw new IllegalStateException("Unexpected value: " + value);
		};
	}

	/**
	 * @param handle
	 * 		Handle to map.
	 *
	 * @return Mapped handle.
	 */
	public static @NotNull org.objectweb.asm.Handle asmHandle(@NotNull Handle handle) {
		int tag = switch (handle.kind()) {
			case Handle.KIND_STATIC_PUT -> H_PUTSTATIC;
			case Handle.KIND_STATIC_GET -> H_GETSTATIC;
			case Handle.KIND_INSTANCE_PUT -> H_PUTFIELD;
			case Handle.KIND_INSTANCE_GET -> H_GETFIELD;
			case Handle.KIND_INVOKE_STATIC -> H_INVOKESTATIC;
			case Handle.KIND_INVOKE_INSTANCE -> H_INVOKEVIRTUAL;
			case Handle.KIND_INVOKE_CONSTRUCTOR -> H_NEWINVOKESPECIAL;
			case Handle.KIND_INVOKE_DIRECT -> H_INVOKESPECIAL;
			case Handle.KIND_INVOKE_INTERFACE -> H_INVOKEINTERFACE;
			default -> throw new IllegalArgumentException("Unsupported handle kind: " + handle.kind());
		};
		String descriptor = handle.type() instanceof MethodType methodType
				? methodType.descriptor()
				: handle.type().descriptor();
		return new org.objectweb.asm.Handle(tag, handle.owner().internalName(), handle.name(), descriptor,
				handle.kind() == Handle.KIND_INVOKE_INTERFACE);
	}

	/**
	 * @param type
	 * 		Type to map.
	 *
	 * @return Mapped type.
	 */
	public static @NotNull org.objectweb.asm.Type asmType(@NotNull Type type) {
		return switch (type) {
			case MethodType methodType -> org.objectweb.asm.Type.getMethodType(methodType.descriptor());
			case ClassType classType -> org.objectweb.asm.Type.getType(classType.descriptor());
		};
	}

	/**
	 * @param type
	 * 		Type to map.
	 *
	 * @return Mapped type, as a string for usage in instruction operands.
	 */
	public static @NotNull String asmTypeOperand(@NotNull ClassType type) {
		return switch (type) {
			case InstanceType instanceType -> instanceType.internalName();
			case ArrayType arrayType -> arrayType.descriptor();
			default -> throw new IllegalArgumentException("Unsupported JVM type operand: " + type);
		};
	}

	/**
	 * @param owner
	 * 		Owner type to map.
	 *
	 * @return Mapped owner type, as a string for usage in instruction operands.
	 */
	public static @NotNull String asmOwner(@NotNull ReferenceType owner) {
		return owner instanceof ArrayType arrayType ? arrayType.descriptor() : owner.internalName();
	}

	/**
	 * @param type
	 * 		Type to map.
	 *
	 * @return Mapped type, or {@code null} if the input is not a reference type.
	 */
	public static @Nullable ReferenceType asReferenceType(@NotNull ClassType type) {
		return type instanceof ReferenceType referenceType ? referenceType : null;
	}

	/**
	 * @param elementType
	 * 		Array element type.
	 *
	 * @return Array load opcode for the given element type.
	 */
	public static int arrayLoadOpcode(@NotNull ClassType elementType) {
		if (elementType instanceof ReferenceType) return org.objectweb.asm.Opcodes.AALOAD;
		return switch (elementType.descriptor()) {
			case "Z", "B" -> org.objectweb.asm.Opcodes.BALOAD;
			case "C" -> CALOAD;
			case "S" -> SALOAD;
			case "F" -> FALOAD;
			case "J" -> LALOAD;
			case "D" -> DALOAD;
			default -> IALOAD;
		};
	}

	/**
	 * @param elementType
	 * 		Array element type.
	 *
	 * @return Array store opcode for the given element type.
	 */
	public static int arrayStoreOpcode(@NotNull ClassType elementType) {
		if (elementType instanceof ReferenceType) return org.objectweb.asm.Opcodes.AASTORE;
		return switch (elementType.descriptor()) {
			case "Z", "B" -> org.objectweb.asm.Opcodes.BASTORE;
			case "C" -> CASTORE;
			case "S" -> SASTORE;
			case "F" -> FASTORE;
			case "J" -> LASTORE;
			case "D" -> DASTORE;
			default -> IASTORE;
		};
	}

	/**
	 * @param mv
	 * 		Writing method visitor.
	 * @param value
	 * 		Value to push.
	 */
	public static void pushInt(@NotNull MethodVisitor mv, int value) {
		switch (value) {
			case -1 -> mv.visitInsn(ICONST_M1);
			case 0 -> mv.visitInsn(ICONST_0);
			case 1 -> mv.visitInsn(ICONST_1);
			case 2 -> mv.visitInsn(ICONST_2);
			case 3 -> mv.visitInsn(ICONST_3);
			case 4 -> mv.visitInsn(ICONST_4);
			case 5 -> mv.visitInsn(ICONST_5);
			default -> {
				if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
					mv.visitIntInsn(BIPUSH, value);
				} else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
					mv.visitIntInsn(SIPUSH, value);
				} else {
					mv.visitLdcInsn(value);
				}
			}
		}
	}

	/**
	 * @param mv
	 * 		Writing method visitor.
	 * @param value
	 * 		Value to push.
	 */
	public static void pushLong(@NotNull MethodVisitor mv, long value) {
		if (value == 0L) {
			mv.visitInsn(org.objectweb.asm.Opcodes.LCONST_0);
		} else if (value == 1L) {
			mv.visitInsn(org.objectweb.asm.Opcodes.LCONST_1);
		} else {
			mv.visitLdcInsn(value);
		}
	}

	/**
	 * @param arguments
	 * 		Bootstrap method arguments to map.
	 *
	 * @return Mapped bootstrap method arguments.
	 */
	public static @NotNull Object[] bootstrapArguments(@NotNull List<Constant> arguments) {
		Object[] mapped = new Object[arguments.size()];
		for (int i = 0; i < arguments.size(); i++)
			mapped[i] = mapBootstrapArgument(arguments.get(i));
		return mapped;
	}

	/**
	 * @param value
	 * 		Bootstrap method argument to map.
	 *
	 * @return Mapped bootstrap method argument, or {@code null} if the input is {@code null} or a {@link NullConstant}.
	 */
	public static @NotNull Object mapBootstrapArgument(@NotNull Constant value) {
		return switch (value) {
			case BoolConstant constant -> constant.value() ? 1 : 0;
			case ByteConstant constant -> constant.value();
			case CharConstant constant -> constant.value();
			case DoubleConstant constant -> constant.value();
			case FloatConstant constant -> constant.value();
			case IntConstant constant -> constant.value();
			case LongConstant constant -> constant.value();
			case ShortConstant constant -> constant.value();
			case StringConstant constant -> constant.value();
			case TypeConstant constant -> asmType(constant.type());
			case HandleConstant constant -> asmHandle(constant.handle());
			default -> throw new UnsupportedOperationException("Unsupported bootstrap argument: " + value);
		};
	}

	/**
	 * @param invokeKind
	 *        {@link Invoke Invoke kind} to map.
	 *
	 * @return Mapped invoke opcode.
	 */
	public static int invokeOpcode(int invokeKind) {
		return switch (invokeKind) {
			case Invoke.VIRTUAL, Invoke.POLYMORPHIC -> org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
			case Invoke.INTERFACE -> org.objectweb.asm.Opcodes.INVOKEINTERFACE;
			case Invoke.DIRECT, Invoke.SUPER -> org.objectweb.asm.Opcodes.INVOKESPECIAL;
			case Invoke.STATIC -> org.objectweb.asm.Opcodes.INVOKESTATIC;
			default -> throw new IllegalArgumentException("Unsupported invoke kind: " + invokeKind);
		};
	}

	/**
	 * @param primitiveType
	 * 		Primitive type to map.
	 *
	 * @return Mapped primitive array type code for use with the {@code NEWARRAY} instruction.
	 */
	public static int primitiveArrayType(@NotNull PrimitiveType primitiveType) {
		return switch (primitiveType.kind()) {
			case PrimitiveKind.T_BOOLEAN -> T_BOOLEAN;
			case PrimitiveKind.T_CHAR -> T_CHAR;
			case PrimitiveKind.T_FLOAT -> T_FLOAT;
			case PrimitiveKind.T_DOUBLE -> T_DOUBLE;
			case PrimitiveKind.T_BYTE -> T_BYTE;
			case PrimitiveKind.T_SHORT -> T_SHORT;
			case PrimitiveKind.T_INT -> T_INT;
			case PrimitiveKind.T_LONG -> T_LONG;
			default -> throw new IllegalArgumentException("Unsupported primitive array type: " + primitiveType);
		};
	}

	/**
	 * @param mv
	 * 		Writing method visitor.
	 * @param declaredArrayType
	 * 		Declared array type to create.
	 *
	 * @return Type of the array created by this instruction, which may be a normalized version of the declared type.
	 */
	public static @NotNull ReferenceType emitNewArray(@NotNull MethodVisitor mv, @NotNull ClassType declaredArrayType) {
		ClassType normalized = normalizeArrayType(declaredArrayType);
		ClassType elementType = arrayElementType(normalized);
		if (elementType instanceof PrimitiveType primitiveType) {
			mv.visitIntInsn(NEWARRAY, primitiveArrayType(primitiveType));
		} else {
			mv.visitTypeInsn(ANEWARRAY, asmTypeOperand(elementType));
		}
		return (ReferenceType) normalized;
	}

	/**
	 * @param type
	 * 		Array type to normalize.
	 *
	 * @return Normalized array type, mapping the input to an {@link ArrayType} if necessary.
	 */
	public static @NotNull ClassType normalizeArrayType(@NotNull ClassType type) {
		return type instanceof ArrayType ? type : new ArrayType(type);
	}

	/**
	 * @param arrayType
	 * 		Array type to get the element type of.
	 *
	 * @return Element type of the given array type, or the input type itself if it is not an array type.
	 */
	public static @NotNull ClassType arrayElementType(@NotNull ClassType arrayType) {
		return arrayType instanceof ArrayType concreteArrayType ? concreteArrayType.componentType() : arrayType;
	}

	/**
	 * @param type
	 * 		Type to check.
	 *
	 * @return {@code true} when the type is a reference type, {@code false} otherwise.
	 */
	public static boolean isReferenceType(@NotNull ClassType type) {
		return type instanceof ReferenceType;
	}

	/**
	 * @param type
	 * 		Type to check.
	 *
	 * @return {@code true} when the type is a wide primitive type ({@code long} or {@code double}), {@code false} otherwise.
	 */
	public static boolean isWideType(@NotNull ClassType type) {
		String descriptor = type.descriptor();
		return "J".equals(descriptor) || "D".equals(descriptor);
	}

	/**
	 * @param type
	 * 		Type to check.
	 *
	 * @return Number of register slots needed to hold a value of the given type.
	 */
	public static int slotSize(@NotNull ClassType type) {
		return isWideType(type) ? 2 : 1;
	}

	/**
	 * @param type
	 * 		Type to check.
	 *
	 * @return {@code true} when the type is a {@code long}, {@code false} otherwise.
	 */
	public static boolean isLongType(@NotNull ClassType type) {
		return "J".equals(type.descriptor());
	}

	/**
	 * @param type
	 * 		Type to check.
	 *
	 * @return {@code true} when the type is a {@code double}, {@code false} otherwise.
	 */
	public static boolean isDoubleType(@NotNull ClassType type) {
		return "D".equals(type.descriptor());
	}

	/**
	 * @param type
	 * 		Type to check.
	 *
	 * @return {@code true} when the type is a {@code float}, {@code false} otherwise.
	 */
	public static boolean isFloatType(@NotNull ClassType type) {
		return "F".equals(type.descriptor());
	}

	/**
	 * @param type
	 * 		Type to check.
	 *
	 * @return {@code true} when the type is a {@code int}, {@code false} otherwise.
	 */
	public static boolean isBooleanType(@NotNull ClassType type) {
		return "Z".equals(type.descriptor());
	}

	/**
	 * @param type
	 * 		Type to check.
	 *
	 * @return {@code true} when the type is a {@code char}, {@code false} otherwise.
	 */
	public static boolean isVoidType(@NotNull ClassType type) {
		return "V".equals(type.descriptor());
	}
}
