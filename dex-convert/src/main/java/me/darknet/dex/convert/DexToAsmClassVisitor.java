package me.darknet.dex.convert;

import me.darknet.dex.convert.ir.IrLowering;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.optimize.IrOptimizationContext;
import me.darknet.dex.convert.ir.optimize.IrOptimizer;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.constant.AnnotationConstant;
import me.darknet.dex.tree.definitions.constant.ArrayConstant;
import me.darknet.dex.tree.definitions.constant.BoolConstant;
import me.darknet.dex.tree.definitions.constant.ByteConstant;
import me.darknet.dex.tree.definitions.constant.CharConstant;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.DoubleConstant;
import me.darknet.dex.tree.definitions.constant.EnumConstant;
import me.darknet.dex.tree.definitions.constant.FloatConstant;
import me.darknet.dex.tree.definitions.constant.HandleConstant;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.LongConstant;
import me.darknet.dex.tree.definitions.constant.MemberConstant;
import me.darknet.dex.tree.definitions.constant.NullConstant;
import me.darknet.dex.tree.definitions.constant.ShortConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.definitions.constant.TypeConstant;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.visitor.DexAnnotationVisitor;
import me.darknet.dex.tree.visitor.DexClassVisitor;
import me.darknet.dex.tree.visitor.DexCodeVisitor;
import me.darknet.dex.tree.visitor.DexConstantVisitor;
import me.darknet.dex.tree.visitor.DexFieldVisitor;
import me.darknet.dex.tree.visitor.DexMethodVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.V1_8;

/**
 * Dex class visitor that traverses a dex class and emits Java bytecode using an ASM {@link ClassVisitor}.
 */
public class DexToAsmClassVisitor extends DexClassVisitor {
	private final ClassVisitor classVisitor;
	private final IrOptimizationContext context;
	private final IrOptimizer optimizer;
	private ClassDefinition currentClass;

	/**
	 * @param classVisitor
	 * 		ASM class visitor to delegate to.
	 * @param context
	 * 		IR optimization context to use when optimizing and emitting methods.
	 * @param optimizer
	 * 		IR optimizer to use when optimizing methods before emitting them.
	 */
	public DexToAsmClassVisitor(@NotNull ClassVisitor classVisitor,
	                            @NotNull IrOptimizationContext context,
	                            @NotNull IrOptimizer optimizer) {
		this.classVisitor = classVisitor;
		this.context = context;
		this.optimizer = optimizer;
	}

	@Override
	public void visit(@NotNull ClassDefinition definition) {
		currentClass = definition;

		String name = definition.getType().internalName();
		String superName = definition.getSuperClass() == null ? null : definition.getSuperClass().internalName();
		String[] interfaces = definition.getInterfaces().stream().map(InstanceType::internalName).toArray(String[]::new);
		classVisitor.visit(V1_8, definition.getAccess(), name, definition.getSignature(), superName, interfaces);
		classVisitor.visitSource(definition.getSourceFile(), null);

		String outerClass = definition.getEnclosingClass() == null ? null : definition.getEnclosingClass().internalName();
		MemberIdentifier outerMethod = definition.getEnclosingMethod();
		String outerMethodName = outerMethod == null ? null : outerMethod.name();
		String outerMethodDesc = outerMethod == null ? null : outerMethod.descriptor();
		if (outerClass != null)
			classVisitor.visitOuterClass(outerClass, outerMethodName, outerMethodDesc);
	}

	@Override
	public void visitInnerClass(@NotNull InnerClass innerClass) {
		classVisitor.visitInnerClass(innerClass.innerClassName(),
				innerClass.anonymous() ? null : innerClass.outerClassName(),
				innerClass.innerName(),
				innerClass.access());
	}

	@Override
	public @Nullable DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
		AnnotationPart part = annotation.annotation();
		AnnotationVisitor annotationVisitor =
				classVisitor.visitAnnotation(part.type().descriptor(), annotation.visibility() > 0);
		return annotationVisitor == null ? null : new AsmAnnotationVisitor(annotationVisitor);
	}

	@Override
	public @Nullable DexFieldVisitor visitField(@NotNull FieldMember field) {
		FieldVisitor fieldVisitor = classVisitor.visitField(field.getAccess(), field.getName(),
				field.getType().descriptor(), field.getSignature(), ConversionSupport.mapConstant(field.getStaticValue()));
		return fieldVisitor == null ? null : new AsmFieldVisitor(fieldVisitor);
	}

	@Override
	public @Nullable DexMethodVisitor visitMethod(@NotNull MethodMember method) {
		String[] exceptions = method.getThrownTypes().isEmpty() ? null : method.getThrownTypes().toArray(String[]::new);
		MethodVisitor methodVisitor = classVisitor.visitMethod(method.getAccess(), method.getName(),
				method.getType().descriptor(), method.getSignature(), exceptions);
		return methodVisitor == null ? null : new AsmMethodVisitor(currentClass, method, methodVisitor, context, optimizer);
	}

	@Override
	public void visitEnd() {
		classVisitor.visitEnd();
	}

	private static final class AsmFieldVisitor extends DexFieldVisitor {

		private final FieldVisitor fieldVisitor;

		private AsmFieldVisitor(@NotNull FieldVisitor fieldVisitor) {
			this.fieldVisitor = fieldVisitor;
		}

		@Override
		public @Nullable DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
			AnnotationPart part = annotation.annotation();
			AnnotationVisitor annotationVisitor =
					fieldVisitor.visitAnnotation(part.type().descriptor(), annotation.visibility() > 0);
			return annotationVisitor == null ? null : new AsmAnnotationVisitor(annotationVisitor);
		}

		@Override
		public void visitEnd() {
			fieldVisitor.visitEnd();
		}
	}

	private static final class AsmMethodVisitor extends DexMethodVisitor {

		private final ClassDefinition owner;
		private final MethodMember method;
		private final MethodVisitor methodVisitor;
		private final IrOptimizationContext context;
		private final IrOptimizer optimizer;

		private AsmMethodVisitor(@NotNull ClassDefinition owner,
		                         @NotNull MethodMember method,
		                         @NotNull MethodVisitor methodVisitor,
		                         @NotNull IrOptimizationContext context,
		                         @NotNull IrOptimizer optimizer) {
			this.owner = owner;
			this.method = method;
			this.methodVisitor = methodVisitor;
			this.context = context;
			this.optimizer = optimizer;
		}

		@Override
		public @Nullable DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
			AnnotationPart part = annotation.annotation();
			AnnotationVisitor annotationVisitor =
					methodVisitor.visitAnnotation(part.type().descriptor(), annotation.visibility() > 0);
			return annotationVisitor == null ? null : new AsmAnnotationVisitor(annotationVisitor);
		}

		@Override
		public @NotNull DexCodeVisitor visitCode(@NotNull Code code) {
			return new AsmCodeVisitor(owner, method, methodVisitor, context, optimizer);
		}

		@Override
		public void visitEnd() {
			methodVisitor.visitEnd();
		}
	}

	private static final class AsmCodeVisitor extends DexCodeVisitor {

		private final ClassDefinition owner;
		private final MethodMember method;
		private final MethodVisitor methodVisitor;
		private final IrOptimizationContext context;
		private final IrOptimizer optimizer;
		private boolean emitted;

		private AsmCodeVisitor(@NotNull ClassDefinition owner,
		                       @NotNull MethodMember method,
		                       @NotNull MethodVisitor methodVisitor,
		                       @NotNull IrOptimizationContext context,
		                       @NotNull IrOptimizer optimizer) {
			this.owner = owner;
			this.method = method;
			this.methodVisitor = methodVisitor;
			this.context = context;
			this.optimizer = optimizer;
		}

		@Override
		public void visit(@NotNull Code code) {
			if (emitted)
				return;

			IrMethod irMethod = context.getMethod(owner.getType(), method.getIdentifier());
			if (irMethod == null) {
				throw new IllegalStateException("Missing IR for method "
						+ owner.getType().internalName() + "." + method.getIdentifier());
			}

			optimizer.optimizeMethod(context, irMethod);
			IrLowering.emit(irMethod, methodVisitor);
			emitted = true;
		}
	}

	private static final class AsmAnnotationVisitor extends DexAnnotationVisitor {

		private final AnnotationVisitor annotationVisitor;

		private AsmAnnotationVisitor(@NotNull AnnotationVisitor annotationVisitor) {
			this.annotationVisitor = annotationVisitor;
		}

		@Override
		public @NotNull DexConstantVisitor visitElement(@NotNull String name, @NotNull Constant value) {
			return new AsmConstantVisitor(annotationVisitor, name);
		}

		@Override
		public void visitEnd() {
			annotationVisitor.visitEnd();
		}
	}

	private static final class AsmConstantVisitor extends DexConstantVisitor {

		private final AnnotationVisitor annotationVisitor;
		private final @Nullable String name;
		private @Nullable AnnotationVisitor pendingArrayVisitor;

		private AsmConstantVisitor(@NotNull AnnotationVisitor annotationVisitor, @Nullable String name) {
			this.annotationVisitor = annotationVisitor;
			this.name = name;
		}

		@Override
		public @Nullable DexAnnotationVisitor visitAnnotationConstant(@NotNull AnnotationConstant constant) {
			AnnotationVisitor child = annotationVisitor.visitAnnotation(name, constant.annotation().type().descriptor());
			return child == null ? null : new AsmAnnotationVisitor(child);
		}

		@Override
		public @Nullable DexConstantVisitor visitArrayConstant(@NotNull ArrayConstant constant) {
			pendingArrayVisitor = annotationVisitor.visitArray(name);
			return pendingArrayVisitor == null ? null : new AsmConstantVisitor(pendingArrayVisitor, null);
		}

		@Override
		public void visitBoolConstant(@NotNull BoolConstant constant) {
			annotationVisitor.visit(name, constant.value());
		}

		@Override
		public void visitByteConstant(@NotNull ByteConstant constant) {
			annotationVisitor.visit(name, constant.value());
		}

		@Override
		public void visitCharConstant(@NotNull CharConstant constant) {
			annotationVisitor.visit(name, constant.value());
		}

		@Override
		public void visitDoubleConstant(@NotNull DoubleConstant constant) {
			annotationVisitor.visit(name, constant.value());
		}

		@Override
		public void visitEnumConstant(@NotNull EnumConstant constant) {
			annotationVisitor.visitEnum(name, constant.field().descriptor(), constant.field().name());
		}

		@Override
		public void visitFloatConstant(@NotNull FloatConstant constant) {
			annotationVisitor.visit(name, constant.value());
		}

		@Override
		public void visitIntConstant(@NotNull IntConstant constant) {
			annotationVisitor.visit(name, constant.value());
		}

		@Override
		public void visitLongConstant(@NotNull LongConstant constant) {
			annotationVisitor.visit(name, constant.value());
		}

		@Override
		public void visitShortConstant(@NotNull ShortConstant constant) {
			annotationVisitor.visit(name, constant.value());
		}

		@Override
		public void visitStringConstant(@NotNull StringConstant constant) {
			annotationVisitor.visit(name, constant.value());
		}

		@Override
		public void visitTypeConstant(@NotNull TypeConstant constant) {
			annotationVisitor.visit(name, ConversionSupport.asmType(constant.type()));
		}

		@Override
		public void visitHandleConstant(@NotNull HandleConstant constant) {
			// Not supported in Java's annotations
		}

		@Override
		public void visitMemberConstant(@NotNull MemberConstant constant) {
			// Not supported in Java's annotations
		}

		@Override
		public void visitNullConstant(@NotNull NullConstant constant) {
			// Not supported in Java's annotations
		}

		@Override
		public void visitEnd() {
			if (pendingArrayVisitor != null) {
				pendingArrayVisitor.visitEnd();
				pendingArrayVisitor = null;
			}
		}
	}
}
