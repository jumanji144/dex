package me.darknet.dex.convert;

import me.darknet.dex.convert.factory.IrBuilderFactory;
import me.darknet.dex.convert.factory.IrOptimizerFactory;
import me.darknet.dex.convert.ir.IrLowering;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.build.IrBuilder;
import me.darknet.dex.convert.ir.optimize.BaseIrOptimizer;
import me.darknet.dex.convert.ir.optimize.IrOptimizationContext;
import me.darknet.dex.convert.ir.optimize.IrOptimizer;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.type.InstanceType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static org.objectweb.asm.Opcodes.V1_8;

/**
 * A Dex/Dalvik to Java class converter that lifts dex code to a simple IR,
 * performs some basic optimizations on the IR, and then lowers it to Java bytecode.
 */
public class DexConversionIr extends AbstractDexConversion {
	private IrBuilderFactory builderFactory = IrBuilder::new;
	private IrOptimizerFactory optimizerFactory = context -> new BaseIrOptimizer();

	public @NotNull IrBuilderFactory getBuilderFactory() {
		return Objects.requireNonNull(builderFactory);
	}

	public void setBuilderFactory(@NotNull IrBuilderFactory builderFactory) {
		this.builderFactory = Objects.requireNonNull(builderFactory);
	}

	public @NotNull IrOptimizerFactory getOptimizerFactory() {
		return Objects.requireNonNull(optimizerFactory);
	}

	public void setOptimizerFactory(@NotNull IrOptimizerFactory optimizerFactory) {
		this.optimizerFactory = Objects.requireNonNull(optimizerFactory);
	}

	@Override
	public @NotNull ConversionResult toClasses(@NotNull DexFile dex) {
		// Build IR and create optimizer for the whole dex, so we can optimize across all classes and methods.
		Map<String, byte[]> classes = new TreeMap<>();
		Map<String, Throwable> errors = new TreeMap<>();
		IrConversionSession session = createSession(dex.definitions(), IrOptimizationContext.ScopeKind.WHOLE_DEX, errors);

		// If the session is null, it means IR building failed for all classes, so we have no IR to optimize or emit.
		if (session == null)
			return new ConversionResult(classes, errors);

		// Optimize the whole program before emitting any classes, so that optimizations that require whole program context can be performed.
		try {
			session.optimizer().optimizeProgram(session.context());
		} catch (Throwable t) {
			// Record optimization errors for all relevant classes.
			for (ClassDefinition cls : session.context().classes())
				errors.putIfAbsent(cls.getType().internalName(), t);
			return new ConversionResult(classes, errors);
		}

		// Emit all classes, recording any errors that occur during emission.
		for (ClassDefinition cls : session.context().classes()) {
			String name = cls.getType().internalName();
			try {
				ClassWriter cw = getWriterFactory().newWriter(cls);
				emitClass(cls, cw, session);
				classes.put(name, cw.toByteArray());
			} catch (Throwable t) {
				errors.put(name, t);
			}
		}
		return new ConversionResult(classes, errors);
	}

	@Override
	public byte @Nullable [] toJavaClass(@NotNull ClassDefinition cls) {
		// Build IR and create optimizer for the class.
		// - As this is a single class and return the bytecode directly, we don't log errors we just rethrow them.
		IrConversionSession session = Objects.requireNonNull(createSession(List.of(cls), IrOptimizationContext.ScopeKind.SINGLE_CLASS, null));
		session.optimizer().optimizeProgram(session.context());

		// Write the single class.
		ClassWriter cw = getWriterFactory().newWriter(cls);
		emitClass(cls, cw, session);
		return cw.toByteArray();
	}

	private void emitClass(@NotNull ClassDefinition cls, @NotNull ClassWriter cw, @NotNull IrConversionSession session) {
		// Base class properties
		String name = cls.getType().internalName();
		String superName = cls.getSuperClass() == null ? null : cls.getSuperClass().internalName();
		String[] interfaces = cls.getInterfaces().stream().map(InstanceType::internalName).toArray(String[]::new);
		cw.visit(V1_8, cls.getAccess(), name, cls.getSignature(), superName, interfaces);

		// Source metadata
		cw.visitSource(cls.getSourceFile(), null);

		// Outer class metadata
		String outerClass = cls.getEnclosingClass() == null ? null : cls.getEnclosingClass().internalName();
		MemberIdentifier outerMethod = cls.getEnclosingMethod();
		String outerMethodName = outerMethod == null ? null : outerMethod.name();
		String outerMethodDesc = outerMethod == null ? null : outerMethod.descriptor();
		if (outerClass != null) cw.visitOuterClass(outerClass, outerMethodName, outerMethodDesc);

		// Inner classes
		for (InnerClass innerClass : cls.getInnerClasses()) {
			cw.visitInnerClass(innerClass.innerClassName(), innerClass.anonymous() ? null : innerClass.outerClassName(), innerClass.innerName(), innerClass.access());
		}

		// Annotations
		for (Annotation annotation : cls.getAnnotations()) {
			AnnotationPart part = annotation.annotation();
			AnnotationVisitor av = ((ClassVisitor) cw).visitAnnotation(part.type().descriptor(), annotation.visibility() > 0);
			ConversionSupport.visitAnnotation(av, annotation);
			av.visitEnd();
		}

		// Fields & Methods
		for (FieldMember field : cls.getFields().values()) {
			FieldVisitor fv = ((ClassVisitor) cw).visitField(field.getAccess(), field.getName(), field.getType().descriptor(),
					field.getSignature(), ConversionSupport.mapConstant(field.getStaticValue()));
			for (Annotation annotation : field.getAnnotations()) {
				AnnotationPart part = annotation.annotation();
				AnnotationVisitor av = fv.visitAnnotation(part.type().descriptor(), annotation.visibility() > 0);
				ConversionSupport.visitAnnotation(av, annotation);
				av.visitEnd();
			}
			fv.visitEnd();
		}
		for (MethodMember method : cls.getMethods().values()) {
			String[] exceptions = method.getThrownTypes().isEmpty() ? null : method.getThrownTypes().toArray(String[]::new);
			MethodVisitor mv = ((ClassVisitor) cw).visitMethod(method.getAccess(), method.getName(), method.getType().descriptor(),
					method.getSignature(), exceptions);
			for (Annotation annotation : method.getAnnotations()) {
				AnnotationPart part = annotation.annotation();
				AnnotationVisitor av = mv.visitAnnotation(part.type().descriptor(), annotation.visibility() > 0);
				ConversionSupport.visitAnnotation(av, annotation);
				av.visitEnd();
			}
			// TODO: In our old R8 based solution we had the option to replace method bodies with stubs if they failed to build,
			//  which allowed us to at least emit the class and its method signatures even if we couldn't convert the method bodies.
			if (method.getCode() != null) {
				// Optimize method IR before lowering.
				IrMethod irMethod = session.context().getMethod(cls.getType(), method.getIdentifier());
				if (irMethod == null)
					throw new IllegalStateException("Missing IR for method " + cls.getType().internalName() + "." + method.getIdentifier());
				session.optimizer().optimizeMethod(session.context(), irMethod);
				IrLowering.emit(irMethod, mv);
			} else {
				mv.visitEnd();
			}
		}

		cw.visitEnd();
	}

	/**
	 * Creates a conversion session by building IR for the given classes and creating an optimizer with the built IR.
	 *
	 * @param classes
	 * 		Classes to build IR for.
	 * @param scopeKind
	 * 		Optimization scope.
	 * @param errors
	 * 		Optional map to report build errors to.
	 * 		If {@code null}, any IR building error will be re-thrown.
	 *
	 * @return Wrapper containing the created optimization context and provided optimizer,
	 * or {@code null} if no classes were successfully built.
	 */
	private @Nullable IrConversionSession createSession(@NotNull List<ClassDefinition> classes,
	                                                    @NotNull IrOptimizationContext.ScopeKind scopeKind,
	                                                    @Nullable Map<String, Throwable> errors) {
		IrBuilderFactory builderFactory = getBuilderFactory();
		IrOptimizerFactory optimizerFactory = getOptimizerFactory();

		// Build IR for all methods, skipping classes that fail to build.
		// We want as much output as possible, so for classes that fail to build
		// we'll just report the error and skip them instead of failing the whole conversion.
		List<ClassDefinition> successfulClasses = new ArrayList<>(classes.size());
		Map<ClassDefinition, List<IrMethod>> methodsByClass = new HashMap<>();
		for (ClassDefinition cls : classes) {
			try {
				// Optimize all non-abstract methods in the class.
				List<IrMethod> methods = new ArrayList<>();
				for (MethodMember method : cls.getMethods().values()) {
					if (method.getCode() == null)
						continue;
					methods.add(builderFactory.newBuilder(method).build());
				}
				successfulClasses.add(cls);
				methodsByClass.put(cls, methods);
			} catch (Throwable t) {
				// If the destination error map is null, we're not able to report the error, so just throw it and fail the whole conversion.
				// We only pass null for single class conversion.
				if (errors == null)
					throw t;
				errors.put(cls.getType().internalName(), t);
			}
		}

		// No results? Nothing to optimize or emit, so just return null.
		if (successfulClasses.isEmpty())
			return null;

		// Create optimizer with the successfully built classes and their IR methods.
		// The optimizer will be used for both whole-dex and single-class conversions,
		// so it needs to be created after building all classes to ensure it has the full context for optimizations.
		IrOptimizationContext context = new IrOptimizationContext(scopeKind, successfulClasses, methodsByClass);
		IrOptimizer optimizer = Objects.requireNonNull(optimizerFactory.newOptimizer(context));
		return new IrConversionSession(context, optimizer);
	}

	private record IrConversionSession(@NotNull IrOptimizationContext context, @NotNull IrOptimizer optimizer) {}
}
