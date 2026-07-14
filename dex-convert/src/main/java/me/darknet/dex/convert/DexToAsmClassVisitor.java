package me.darknet.dex.convert;

import me.darknet.dex.convert.ir.IrLowering;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.IrLoweringResult;
import me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy;
import me.darknet.dex.convert.ir.lowering.JvmLambdaMetadata;
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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.V1_8;

/**
 * Dex class visitor that traverses a dex class and emits Java bytecode using an ASM {@link ClassVisitor}.
 */
public class DexToAsmClassVisitor extends DexClassVisitor {
	private final ClassVisitor classVisitor;
	private final IrOptimizationContext context;
	private final IrOptimizer optimizer;
	private final Consumer<List<ConversionDiagnostic>> diagnosticSink;
	private final JvmLoweringPolicy loweringPolicy;
	private final JvmLambdaMetadata lambdaMetadata;
	private ClassDefinition currentClass;
	private Set<FieldMember> staticInitializerAssignments = Set.of();

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
		this(classVisitor, context, optimizer, ignored -> { }, JvmLoweringPolicy.DETERMINISTIC_LOCAL);
	}

	public DexToAsmClassVisitor(@NotNull ClassVisitor classVisitor,
	                            @NotNull IrOptimizationContext context,
	                            @NotNull IrOptimizer optimizer,
	                            @NotNull Consumer<List<ConversionDiagnostic>> diagnosticSink) {
		this(classVisitor, context, optimizer, diagnosticSink, JvmLoweringPolicy.DETERMINISTIC_LOCAL,
				JvmLambdaMetadata.empty());
	}

	public DexToAsmClassVisitor(@NotNull ClassVisitor classVisitor,
	                            @NotNull IrOptimizationContext context,
	                            @NotNull IrOptimizer optimizer,
	                            @NotNull Consumer<List<ConversionDiagnostic>> diagnosticSink,
	                            @NotNull JvmLoweringPolicy loweringPolicy) {
		this(classVisitor, context, optimizer, diagnosticSink, loweringPolicy, JvmLambdaMetadata.empty());
	}

	public DexToAsmClassVisitor(@NotNull ClassVisitor classVisitor,
	                            @NotNull IrOptimizationContext context,
	                            @NotNull IrOptimizer optimizer,
	                            @NotNull Consumer<List<ConversionDiagnostic>> diagnosticSink,
	                            @NotNull JvmLoweringPolicy loweringPolicy,
	                            @NotNull JvmLambdaMetadata lambdaMetadata) {
		this.classVisitor = classVisitor;
		this.context = context;
		this.optimizer = optimizer;
		this.diagnosticSink = diagnosticSink;
		this.loweringPolicy = loweringPolicy;
		this.lambdaMetadata = lambdaMetadata;
	}

	@Override
	public void visit(@NotNull ClassDefinition definition) {
		currentClass = definition;
		staticInitializerAssignments = ConversionSupport.staticInitializerAssignments(definition);

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
				field.getType().descriptor(), field.getSignature(), ConversionSupport.mapFieldConstant(field.getAccess(),
						field.getStaticValue(), staticInitializerAssignments.contains(field)));
		return fieldVisitor == null ? null : new AsmFieldVisitor(fieldVisitor);
	}

	@Override
	public @Nullable DexMethodVisitor visitMethod(@NotNull MethodMember method) {
		String[] exceptions = method.getThrownTypes().isEmpty() ? null : method.getThrownTypes().toArray(String[]::new);
		MethodVisitor methodVisitor = classVisitor.visitMethod(ConversionSupport.mapMethodAccess(method.getAccess()), method.getName(),
				method.getType().descriptor(), method.getSignature(), exceptions);
			return methodVisitor == null ? null : new AsmMethodVisitor(currentClass, method, methodVisitor, context, optimizer,
					diagnosticSink, loweringPolicy, lambdaMetadata);
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
		private final Consumer<List<ConversionDiagnostic>> diagnosticSink;
		private final JvmLoweringPolicy loweringPolicy;
		private final JvmLambdaMetadata lambdaMetadata;

		private AsmMethodVisitor(@NotNull ClassDefinition owner,
		                         @NotNull MethodMember method,
		                         @NotNull MethodVisitor methodVisitor,
		                         @NotNull IrOptimizationContext context,
		                         @NotNull IrOptimizer optimizer,
		                         @NotNull Consumer<List<ConversionDiagnostic>> diagnosticSink,
		                         @NotNull JvmLoweringPolicy loweringPolicy,
		                         @NotNull JvmLambdaMetadata lambdaMetadata) {
			this.owner = owner;
			this.method = method;
			this.methodVisitor = methodVisitor;
			this.context = context;
			this.optimizer = optimizer;
			this.diagnosticSink = diagnosticSink;
			this.loweringPolicy = loweringPolicy;
			this.lambdaMetadata = lambdaMetadata;
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
				return new AsmCodeVisitor(owner, method, methodVisitor, context, optimizer, diagnosticSink,
						loweringPolicy, lambdaMetadata);
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
		private final Consumer<List<ConversionDiagnostic>> diagnosticSink;
		private final JvmLoweringPolicy loweringPolicy;
		private final JvmLambdaMetadata lambdaMetadata;
		private boolean emitted;

		private AsmCodeVisitor(@NotNull ClassDefinition owner,
		                       @NotNull MethodMember method,
		                       @NotNull MethodVisitor methodVisitor,
		                       @NotNull IrOptimizationContext context,
		                       @NotNull IrOptimizer optimizer,
		                       @NotNull Consumer<List<ConversionDiagnostic>> diagnosticSink,
		                       @NotNull JvmLoweringPolicy loweringPolicy,
		                       @NotNull JvmLambdaMetadata lambdaMetadata) {
			this.owner = owner;
			this.method = method;
			this.methodVisitor = methodVisitor;
			this.context = context;
			this.optimizer = optimizer;
			this.diagnosticSink = diagnosticSink;
			this.loweringPolicy = loweringPolicy;
			this.lambdaMetadata = lambdaMetadata;
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
			if (loweringPolicy == JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED) {
				emitAggressiveOrFallback(irMethod);
			} else {
				IrLoweringResult result = IrLowering.emitWithResult(irMethod, methodVisitor, loweringPolicy, lambdaMetadata);
				diagnosticSink.accept(result.diagnostics());
			}
			emitted = true;
		}

		private void emitAggressiveOrFallback(@NotNull IrMethod irMethod) {
			MethodNode aggressive = newMethodNode();
			try {
				IrLoweringResult result = IrLowering.emitWithResult(irMethod, aggressive, loweringPolicy, lambdaMetadata);
				int normalizedRanges = JvmLocalMaterializationCleanup.normalizeResourceRangeStarts(aggressive);
				int normalizedConstructors = JvmLocalMaterializationCleanup.normalizeResourceConstructors(aggressive);
				JvmMethodShapeValidator.Validation validation =
						JvmMethodShapeValidator.validate(owner.getType().internalName(), aggressive);
				if (validation.valid()) {
					// The post-emission materialization cleanup is a late, optional
					// optimization.  Keep a validated snapshot so a cleanup pass that
					// invalidates an exception-local route can be discarded without
					// throwing away an otherwise valid aggressive method.  This is
					// especially important for nested resource handlers: their close
					// instructions may be structurally equal while belonging to
					// different protected paths.
					MethodNode validatedAggressive = newMethodNode();
					aggressive.accept(validatedAggressive);
					int removedConstructorThrows = JvmLocalMaterializationCleanup.removeOneUseConstructorThrows(aggressive);
					int removedConstructorSlices = JvmLocalMaterializationCleanup.removeOneUseConstructorCopies(aggressive);
					int removedStaticReceiverSlices = JvmLocalMaterializationCleanup.removeOneUseStaticReceiverCopies(aggressive);
					int removedPairs = JvmLocalMaterializationCleanup.removeProvenMaterialization(aggressive, true);
					int retargetedHandlerBridges = JvmLocalMaterializationCleanup.retargetEquivalentHandlerStoreBridges(aggressive);
					removedConstructorSlices += JvmLocalMaterializationCleanup.removeOneUseConstructorCopies(aggressive);
					for (int pass = 0; pass < 16; pass++) {
						int removed = JvmLocalMaterializationCleanup.removeOneUseConstructorThrows(aggressive);
						if (removed == 0) break;
						removedConstructorThrows += removed;
					}
					removedStaticReceiverSlices += JvmLocalMaterializationCleanup.removeOneUseStaticReceiverCopies(aggressive);
					int relocatedProtectedThrows = JvmLocalMaterializationCleanup.relocateProtectedThrowBranches(aggressive);
					int duplicatedCleanupTails = JvmLocalMaterializationCleanup.duplicateSharedNormalCleanupTails(aggressive);
					int removedUnreachable = JvmLocalMaterializationCleanup.removeUnreachableInstructions(aggressive);
					for (int pass = 0; pass < 4; pass++) {
						int relocated = JvmLocalMaterializationCleanup.relocateProtectedThrowBranches(aggressive);
						if (relocated == 0) break;
						relocatedProtectedThrows += relocated;
					}
					int mergedProtectedThrows = 0;
					for (int pass = 0; pass < 4; pass++) {
						int merged = JvmLocalMaterializationCleanup.mergeEquivalentProtectedThrowBlocks(aggressive);
						if (merged == 0) break;
						mergedProtectedThrows += merged;
					}
					int retargetedCloseBoundaries = JvmLocalMaterializationCleanup.retargetNullCloseBoundaryBranches(aggressive);
					int removedCloseBoundaryBridges = JvmLocalMaterializationCleanup.removeRangeEndCloseGotoBridges(aggressive);
					int normalizedNullCloseRanges = JvmLocalMaterializationCleanup.normalizeNullCloseRangeEnds(aggressive);
					int shapedCloseGuards = JvmLocalMaterializationCleanup.shapeCloseGuardConditionals(aggressive);
					int restoredCloseJoins = JvmLocalMaterializationCleanup.restoreNullCloseJoinGotos(aggressive);
					int preservedResourceAliases = JvmLocalMaterializationCleanup.preserveProtectedResourceParameterAlias(aggressive);
					int widenedOuterResourceRanges = JvmLocalMaterializationCleanup.widenOuterRangesToResourceAlias(aggressive);
					int decoupledCloseGuards = JvmLocalMaterializationCleanup.decoupleCloseGuardTargets(aggressive);
					// Exception relocation and tail normalization can turn a previously
					// non-inlineable producer/consumer pair into a same-profile pair.
					// Re-run the bounded invocation proof after the authoritative layout
					// has settled.  This is still one-consumer, stack-local fusion; it
					// does not introduce stack carry across labels or handlers.
					int lateInvokeSlices = JvmLocalMaterializationCleanup.removeOneUseInvokeCopies(aggressive);
					int extendedCatchFinallyRanges = JvmLocalMaterializationCleanup.extendCatchToFinallyRanges(aggressive);
					int removedRedundantExceptionRanges = JvmLocalMaterializationCleanup.removeRedundantContainedExceptionRanges(aggressive);
					int removedBooleanBranchStores = JvmLocalMaterializationCleanup.removeBooleanStoreLoadsBeforeBranches(aggressive);
					int orderedNestedRanges = JvmLocalMaterializationCleanup.orderNestedExceptionRanges(aggressive);
					removedConstructorThrows += JvmLocalMaterializationCleanup.removeOneUseConstructorThrows(aggressive);
					int totalCleanup = removedPairs + removedConstructorThrows + removedConstructorSlices + removedStaticReceiverSlices
							+ retargetedHandlerBridges + duplicatedCleanupTails + relocatedProtectedThrows
							+ mergedProtectedThrows + retargetedCloseBoundaries + removedCloseBoundaryBridges
							+ normalizedNullCloseRanges + shapedCloseGuards + restoredCloseJoins
							+ preservedResourceAliases
							+ widenedOuterResourceRanges
							+ decoupledCloseGuards + lateInvokeSlices
							+ extendedCatchFinallyRanges
							+ removedRedundantExceptionRanges
							+ removedBooleanBranchStores
							+ orderedNestedRanges
							+ removedUnreachable;
					if (totalCleanup > 0) {
						validation = JvmMethodShapeValidator.validate(owner.getType().internalName(), aggressive);
						if (!validation.valid()) {
							String cleanupFailure = validation.reason();
							aggressive = validatedAggressive;
							List<ConversionDiagnostic> diagnostics = new ArrayList<>(result.diagnostics());
							int dexOffset = irMethod.blocks().stream().mapToInt(block -> block.startOffset()).min().orElse(-1);
							diagnostics.add(new ConversionDiagnostic(
									owner.getType().internalName(), irMethod.source().toString(), dexOffset,
									ConversionDiagnostic.Severity.INFO,
									ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION,
									"Rejected aggressive local materialization cleanup: " + cleanupFailure, null));
							diagnosticSink.accept(List.copyOf(diagnostics));
							aggressive.accept(methodVisitor);
							return;
						}
					}
					List<ConversionDiagnostic> diagnostics = new ArrayList<>(result.diagnostics());
					if (normalizedRanges > 0) {
						int dexOffset = irMethod.blocks().stream().mapToInt(block -> block.startOffset()).min().orElse(-1);
						diagnostics.add(new ConversionDiagnostic(
								owner.getType().internalName(), irMethod.source().toString(), dexOffset,
								ConversionDiagnostic.Severity.WARNING,
								ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION,
								"Applied aggressive resource-range start normalization: " + normalizedRanges + " range(s)", null));
					}
					if (normalizedConstructors > 0) {
						int dexOffset = irMethod.blocks().stream().mapToInt(block -> block.startOffset()).min().orElse(-1);
						diagnostics.add(new ConversionDiagnostic(
								owner.getType().internalName(), irMethod.source().toString(), dexOffset,
								ConversionDiagnostic.Severity.WARNING,
								ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION,
								"Applied aggressive resource-constructor shaping: " + normalizedConstructors + " constructor(s)", null));
					}
					if (totalCleanup > 0) {
						int dexOffset = irMethod.blocks().stream().mapToInt(block -> block.startOffset()).min().orElse(-1);
						diagnostics.add(new ConversionDiagnostic(
								owner.getType().internalName(), irMethod.source().toString(), dexOffset,
								ConversionDiagnostic.Severity.WARNING,
								ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION,
								"Applied aggressive local materialization cleanup: removed "
								+ removedPairs + " proven materialization(s) and "
								+ removedConstructorThrows + " constructor-to-throw slice(s) and "
								+ removedConstructorSlices + " constructor expression slice(s) and "
									+ removedStaticReceiverSlices + " static receiver slice(s) and "
									+ retargetedHandlerBridges + " equivalent handler bridge(s) and "
									+ duplicatedCleanupTails + " shared normal cleanup tail(s) and "
									+ relocatedProtectedThrows + " protected throw branch(es) and "
									+ mergedProtectedThrows + " merged protected throw block(s) and "
								+ retargetedCloseBoundaries + " close-boundary branch(es) and "
								+ removedCloseBoundaryBridges + " close-boundary bridge(s) and "
								+ normalizedNullCloseRanges + " null-close range end normalization(s) and "
								+ shapedCloseGuards + " shaped close guard(s) and "
								+ restoredCloseJoins + " restored close join(s) and "
								+ preservedResourceAliases + " preserved resource alias(es) and "
								+ widenedOuterResourceRanges + " widened outer resource range(s) and "
								+ decoupledCloseGuards + " decoupled close guard(s) and "
								+ lateInvokeSlices + " late expression slice(s) and "
								+ extendedCatchFinallyRanges + " catch-to-finally range extension(s) and "
								+ removedRedundantExceptionRanges + " redundant contained exception range(s) and "
								+ removedBooleanBranchStores + " boolean branch materialization(s) and "
								+ orderedNestedRanges + " nested exception-range ordering pass(es) and "
								+ removedUnreachable + " unreachable instruction(s)", null));
					}
					diagnosticSink.accept(List.copyOf(diagnostics));
					aggressive.accept(methodVisitor);
					return;
				}
				emitFallback(irMethod, result.diagnostics(), validation.reason());
			} catch (Throwable failure) {
				emitFallback(irMethod, List.of(),
						"aggressive method emission failed: " + failure.getClass().getSimpleName()
								+ (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
			}
		}

		private void emitFallback(@NotNull IrMethod irMethod,
		                          @NotNull List<ConversionDiagnostic> aggressiveDiagnostics,
		                          @NotNull String reason) {
			List<ConversionDiagnostic> fallbackDiagnostics = new ArrayList<>(aggressiveDiagnostics);
			MethodNode guarded = newMethodNode();
			try {
				IrLoweringResult guardedResult = IrLowering.emitWithResult(irMethod, guarded,
						JvmLoweringPolicy.GUARDED_OPTIMIZED, lambdaMetadata);
				fallbackDiagnostics.addAll(guardedResult.diagnostics());
				JvmMethodShapeValidator.Validation guardedValidation =
						JvmMethodShapeValidator.validate(owner.getType().internalName(), guarded);
				if (guardedValidation.valid()) {
					int dexOffset = irMethod.blocks().stream().mapToInt(block -> block.startOffset()).min().orElse(-1);
					int removedPairs = JvmLocalMaterializationCleanup.removeProvenMaterialization(guarded);
					if (removedPairs > 0) {
						JvmMethodShapeValidator.Validation cleanupValidation =
								JvmMethodShapeValidator.validate(owner.getType().internalName(), guarded);
						if (!cleanupValidation.valid()) {
							reason = reason + "; local cleanup validation failed: " + cleanupValidation.reason();
							emitDeterministicFallback(irMethod, fallbackDiagnostics, reason);
							return;
						} else {
							fallbackDiagnostics.add(new ConversionDiagnostic(
									owner.getType().internalName(), irMethod.source().toString(), dexOffset,
									ConversionDiagnostic.Severity.WARNING,
									ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION,
									"Applied aggressive local materialization cleanup: removed "
										+ removedPairs + " proven materialization(s)", null));
						}
					}
					fallbackDiagnostics.add(new ConversionDiagnostic(
							owner.getType().internalName(), irMethod.source().toString(), dexOffset,
							ConversionDiagnostic.Severity.WARNING,
							ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION,
							"Aggressive method structural validation failed; retried guarded lowering: " + reason,
							null));
					diagnosticSink.accept(List.copyOf(fallbackDiagnostics));
					guarded.accept(methodVisitor);
					return;
				}
				reason = reason + "; guarded validation failed: " + guardedValidation.reason();
			} catch (Throwable guardedFailure) {
				reason = reason + "; guarded emission failed: " + guardedFailure.getClass().getSimpleName()
						+ (guardedFailure.getMessage() == null ? "" : ": " + guardedFailure.getMessage());
			}
			emitDeterministicFallback(irMethod, fallbackDiagnostics, reason);
		}

		private void emitDeterministicFallback(@NotNull IrMethod irMethod,
		                                       @NotNull List<ConversionDiagnostic> aggressiveDiagnostics,
		                                       @NotNull String reason) {
			MethodNode deterministic = newMethodNode();
			IrLoweringResult fallback = IrLowering.emitWithResult(irMethod, deterministic,
					JvmLoweringPolicy.DETERMINISTIC_LOCAL, lambdaMetadata);
			List<ConversionDiagnostic> diagnostics = new ArrayList<>(aggressiveDiagnostics);
			diagnostics.addAll(fallback.diagnostics());
			int dexOffset = irMethod.blocks().stream().mapToInt(block -> block.startOffset()).min().orElse(-1);
			diagnostics.add(new ConversionDiagnostic(
					owner.getType().internalName(), irMethod.source().toString(), dexOffset,
					ConversionDiagnostic.Severity.WARNING,
					ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION,
					"Aggressive method structural validation failed; retried deterministic lowering: " + reason,
					null));
			diagnosticSink.accept(List.copyOf(diagnostics));
			deterministic.accept(methodVisitor);
		}

		private MethodNode newMethodNode() {
			String[] exceptions = method.getThrownTypes().isEmpty()
					? null : method.getThrownTypes().toArray(String[]::new);
			return new MethodNode(Opcodes.ASM9,
					ConversionSupport.mapMethodAccess(method.getAccess()), method.getName(),
					method.getType().descriptor(), method.getSignature(), exceptions);
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
