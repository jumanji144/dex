package me.darknet.dex.convert;

import me.darknet.dex.convert.factory.IrBuilderFactory;
import me.darknet.dex.convert.factory.IrOptimizerFactory;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.build.IrBuilder;
import me.darknet.dex.convert.ir.analysis.CompositeIrTypeResolver;
import me.darknet.dex.convert.ir.analysis.DexIrTypeResolver;
import me.darknet.dex.convert.ir.analysis.IrTypeResolver;
import me.darknet.dex.convert.ir.analysis.ReflectionIrTypeResolver;
import me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy;
import me.darknet.dex.convert.ir.lowering.JvmLambdaMetadata;
import me.darknet.dex.convert.ir.optimize.BaseIrOptimizer;
import me.darknet.dex.convert.ir.optimize.IrOptimizationContext;
import me.darknet.dex.convert.ir.optimize.IrOptimizer;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A Dex/Dalvik to Java class converter that lifts dex code to a simple IR,
 * performs some basic optimizations on the IR, and then lowers it to Java bytecode.
 */
public class DexConversionIr extends AbstractDexConversion {
	private IrBuilderFactory builderFactory = IrBuilder::new;
	private IrOptimizerFactory optimizerFactory = context -> new BaseIrOptimizer();
	private @Nullable IrTypeResolver typeResolver;
	private JvmLoweringPolicy jvmLoweringPolicy = JvmLoweringPolicy.DETERMINISTIC_LOCAL;

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

	public @Nullable IrTypeResolver getTypeResolver() {
		return typeResolver;
	}

	public void setTypeResolver(@Nullable IrTypeResolver typeResolver) {
		this.typeResolver = typeResolver;
	}

	public @NotNull JvmLoweringPolicy getJvmLoweringPolicy() {
		return jvmLoweringPolicy;
	}

	public void setJvmLoweringPolicy(@NotNull JvmLoweringPolicy policy) {
		this.jvmLoweringPolicy = Objects.requireNonNull(policy);
	}

	@Override
	public @NotNull ConversionResult toClasses(@NotNull DexFile dex) {
		// Build IR and create optimizer for the whole dex, so we can optimize across all classes and methods.
		Map<String, byte[]> classes = new TreeMap<>();
		Map<String, Throwable> errors = new TreeMap<>();
		Map<String, List<ConversionDiagnostic>> diagnostics = new TreeMap<>();
		IrConversionSession session = createSession(dex.definitions(), IrOptimizationContext.ScopeKind.WHOLE_DEX, errors, diagnostics);

		// If the session is null, it means IR building failed for all classes, so we have no IR to optimize or emit.
		if (session == null)
			return new ConversionResult(classes, errors, diagnostics);

		// Optimize the whole program before emitting any classes, so that optimizations that require whole program context can be performed.
		try {
			session.optimizer().optimizeProgram(session.context());
		} catch (Throwable t) {
			// Record optimization errors for all relevant classes.
			for (ClassDefinition cls : session.context().classes())
				errors.putIfAbsent(cls.getType().internalName(), t);
			return new ConversionResult(classes, errors, diagnostics);
		}

		// Emit all classes, recording any errors that occur during emission.
		for (ClassDefinition cls : session.context().classes()) {
			String name = cls.getType().internalName();
			try {
				byte[] bytecode;
				try {
					bytecode = emitVerifiedClass(cls, session, diagnostics, jvmLoweringPolicy);
				} catch (Throwable aggressiveFailure) {
					if (!jvmLoweringPolicy.aggressiveCleanup()) throw aggressiveFailure;
					recordAggressiveRetry(name, diagnostics, aggressiveFailure);
					try {
						bytecode = emitVerifiedClass(cls, session, diagnostics,
								JvmLoweringPolicy.DETERMINISTIC_LOCAL);
					} catch (Throwable deterministicFailure) {
						deterministicFailure.addSuppressed(aggressiveFailure);
						throw deterministicFailure;
					}
				}
				classes.put(name, bytecode);
			} catch (Throwable t) {
				errors.put(name, t);
			}
		}
		return new ConversionResult(classes, errors, diagnostics);
	}

	@Override
	public byte @Nullable [] toJavaClass(@NotNull ClassDefinition cls) {
		// Build IR and create optimizer for the class.
		// - As this is a single class and return the bytecode directly, we don't log errors we just rethrow them.
		IrConversionSession session = Objects.requireNonNull(createSession(List.of(cls), IrOptimizationContext.ScopeKind.SINGLE_CLASS, null, null));
		session.optimizer().optimizeProgram(session.context());

		try {
			return emitVerifiedClass(cls, session, null, jvmLoweringPolicy);
		} catch (Throwable aggressiveFailure) {
			if (!jvmLoweringPolicy.aggressiveCleanup()) throw aggressiveFailure;
			try {
				return emitVerifiedClass(cls, session, null, JvmLoweringPolicy.DETERMINISTIC_LOCAL);
			} catch (Throwable deterministicFailure) {
				deterministicFailure.addSuppressed(aggressiveFailure);
				throw deterministicFailure;
			}
		}
	}

	private byte @NotNull [] emitVerifiedClass(@NotNull ClassDefinition cls,
	                                           @NotNull IrConversionSession session,
	                                           @Nullable Map<String, List<ConversionDiagnostic>> diagnostics,
	                                           @NotNull JvmLoweringPolicy policy) {
		ClassWriter cw = getWriterFactory().newWriter(cls);
		emitClass(cls, cw, session, diagnostics, policy);
		byte[] bytecode = cw.toByteArray();
		JvmClassVerifier.Verification verification = JvmClassVerifier.verify(bytecode);
		if (verification.dependencyUnavailable() && diagnostics != null) {
			String name = cls.getType().internalName();
			diagnostics.computeIfAbsent(name, ignored -> new ArrayList<>()).add(new ConversionDiagnostic(
					name, "<class>", -1, ConversionDiagnostic.Severity.WARNING,
					ConversionDiagnostic.Kind.VERIFIER,
					"ASM verification could not resolve an external type", verification.unavailable()));
		}
		return bytecode;
	}

	private void recordAggressiveRetry(@NotNull String className,
	                                   @NotNull Map<String, List<ConversionDiagnostic>> diagnostics,
	                                   @NotNull Throwable failure) {
		diagnostics.computeIfAbsent(className, ignored -> new ArrayList<>()).add(new ConversionDiagnostic(
				className, "<class>", -1, ConversionDiagnostic.Severity.WARNING,
				ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION,
				"Aggressive emission failed; retried deterministic lowering", failure));
	}

	private void emitClass(@NotNull ClassDefinition cls, @NotNull ClassWriter cw,
	                       @NotNull IrConversionSession session,
	                       @Nullable Map<String, List<ConversionDiagnostic>> diagnostics,
	                       @NotNull JvmLoweringPolicy policy) {
		String name = cls.getType().internalName();
		JvmLambdaMetadata lambdaMetadata = JvmLambdaMetadata.fromDefinitions(session.context().classes());
		cls.accept(new DexToAsmClassVisitor(cw, session.context(), session.optimizer(), emitted -> {
			if (diagnostics != null && !emitted.isEmpty())
				diagnostics.computeIfAbsent(name, ignored -> new ArrayList<>()).addAll(emitted);
		}, policy, lambdaMetadata));
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
	                                                    @Nullable Map<String, Throwable> errors,
	                                                    @Nullable Map<String, List<ConversionDiagnostic>> diagnostics) {
		IrBuilderFactory builderFactory = getBuilderFactory();
		IrOptimizerFactory optimizerFactory = getOptimizerFactory();
		IrTypeResolver resolver = typeResolver == null
				? new CompositeIrTypeResolver(new DexIrTypeResolver(classes), new ReflectionIrTypeResolver())
				: typeResolver;

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
					methods.add(builderFactory.newBuilder(method, resolver).build());
				}
				successfulClasses.add(cls);
				methodsByClass.put(cls, methods);
				if (diagnostics != null) {
					List<ConversionDiagnostic> classDiagnostics = methods.stream()
							.flatMap(method -> method.diagnostics().stream()).toList();
					if (!classDiagnostics.isEmpty())
						diagnostics.put(cls.getType().internalName(), new ArrayList<>(classDiagnostics));
				}
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
