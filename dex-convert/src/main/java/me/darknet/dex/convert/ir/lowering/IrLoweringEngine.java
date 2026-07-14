package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ConversionDiagnostic;
import me.darknet.dex.convert.ir.IrLoweringResult;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrParameter;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.convert.ir.value.IrType;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.ArrayLengthInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
import me.darknet.dex.tree.definitions.instructions.CompareInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.SparseSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.UnaryInstruction;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Stateful implementation of the IR-to-JVM lowering pipeline.
 */
public final class IrLoweringEngine {
	private final IrMethod method;
	private final MethodVisitor mv;
	private final IrLoweringContext context;
	private final InstructionTrackingMethodVisitor instructionTracker;
	private final IrEmissionState emissionState;
	private final IrLoweringLayout layout;
	private final IrBlockEmitter blockEmitter = new IrBlockEmitter();
	private final IrSpecialChainEmitter specialChainEmitter = new IrSpecialChainEmitter();
	private final IrOperationEmitter operationEmitter;
	private final Map<IrBlock, Label> labels;
	private JvmExceptionLayoutPlan exceptionLayoutPlan;
	private final Map<IrBlock, Label> protectedBoundaryLabels = new LinkedHashMap<>();
	private final Label endLabel;
	private final Map<JvmHandlerStubKey, Label> handlerStubLabels = new LinkedHashMap<>();
	private final Map<IrBlock, Label> simpleHandlerStubLabels = new LinkedHashMap<>();
	private final Map<IrBlock, IrBlock> sharedHandlerStubSources = new IdentityHashMap<>();
	private final Map<IrBlock, HandlerTail> handlerTails = new LinkedHashMap<>();
	private final Set<IrBlock> skippedHandlerTailBlocks = new HashSet<>();
	private final Set<IrBlock> deferredNullThrowBlocks = new HashSet<>();
	private final Set<IrExceptionRegion> relocatedNullResourceRegions = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<IrBlock, List<IrBlock>> deferredNullThrowInsertions = new HashMap<>();
	private final Map<IrExceptionRegion, Label> tryStartLabels = new IdentityHashMap<>();
	private final Map<IrBlock, Label> tryStartLabelsByBlock = new HashMap<>();
	private final Set<IrBlock> deferredNormalTailBlocks = new HashSet<>();
	private final OperandStackState operandStackState = new OperandStackState();
	private final JvmLoweringPolicy policy;
	private final JvmLambdaMetadata lambdaMetadata;
	private final Map<IrPhi, IrValue> initializedPhiValues = new HashMap<>();
	private final Set<IrBlock> stubbedHandlers = new HashSet<>();
	private final Set<IrBlock> directHandlerEntries = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<Integer, IrBlock> blockByOffset;
	private final Set<IrOp> emittedOps;
	private final Set<IrOp> directReturnOperands = new HashSet<>();
	private final Set<IrBlock> fullyInlinedReturnBlocks = new HashSet<>();
	private final Set<IrEffect> emittedEffects;
	private final Map<IrOp, IrOp> constructorByReceiver = new HashMap<>();
	private final Set<IrOp> inlineConstructedReceivers = new HashSet<>();
	private final IrExpressionPlanner expressionPlanner = new IrExpressionPlanner();
	private LoweringUseGraph useGraph;
	private final int registerLocalBase;
	private final IrLoweringAnalysis analysis;
	private JvmLoweringFacts loweringFacts;
	private JvmOptimizationGuards optimizationGuards;
	private JvmOptimizationPlan optimizationPlan;
	private final JvmOptimizationFeatures optimizationFeatures;
	private final Map<IrExceptionRegion, JvmCleanupRegionPlan> cleanupPlans = new IdentityHashMap<>();
	private final Map<IrExceptionRegion, JvmCleanupRegionPlan> protectedRangePlans = new IdentityHashMap<>();
	private final List<JvmCleanupCompositePlan> cleanupCompositePlans = new ArrayList<>();
	private final Map<IrExceptionRegion, JvmCleanupCompositePlan> cleanupCompositeByRegion = new IdentityHashMap<>();
	private final List<JvmCleanupTailPlan> cleanupTailPlans = new ArrayList<>();
	private final Map<IrBlock, JvmCleanupTailPlan> sharedCleanupTails = new IdentityHashMap<>();
	private final Map<JvmCleanupTailPlan, Label> cleanupTailLabels = new LinkedHashMap<>();
	private final Set<IrBlock> skippedCleanupTailBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<JvmMonitorRegionPlan> monitorRegionPlans = new ArrayList<>();
	private final Map<IrBlock, JvmMonitorRegionPlan> sharedMonitorExitBlocks = new IdentityHashMap<>();
	private final Map<JvmMonitorRegionPlan, Label> monitorExitLabels = new LinkedHashMap<>();
	private final Set<IrBlock> skippedMonitorExitBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<IrBlock> emittedBlockLabels = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<IrBlock> layoutTransparentBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<Label> emittedPrimaryExceptionBoundaries = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<IrBlock> referencedBlockTargets = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<IrOp, JvmSingleUsePlan> singleUseByOperation = new IdentityHashMap<>();
	private final Map<IrStmt, Map<Integer, JvmSingleUsePlan>> singleUseByConsumer = new IdentityHashMap<>();
	private final List<JvmSingleUsePlan> singleUsePlans = new ArrayList<>();
	private final List<JvmLoopShapePlan> loopShapePlans = new ArrayList<>();
	private final Map<IrBlock, JvmLoopShapePlan> loopShapeByBlock = new IdentityHashMap<>();
	private JvmMaterializationPlan materializationPlan;
	private final List<ConversionDiagnostic> loweringDiagnostics = new ArrayList<>();
	private final Set<IrUnknown> reportedUnknowns = Collections.newSetFromMap(new IdentityHashMap<>());
	private boolean tainted;
	private int nextLocal;
	private IrStmt currentStatement;
	private IrBlock currentBlock;
	private JvmSingleUseCandidate activeExpressionSlice;
	private IrOp activeExpressionOperation;

	private record HandlerTail(@NotNull IrBlock root, @NotNull IrBlock target, @NotNull Label label) {}

	private record DirectReturn(@NotNull IrTerminator terminator, @NotNull IrValue value) {}

	private static final Handle LAMBDA_METAFACTORY = new Handle(H_INVOKESTATIC,
			"java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
					+ "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

	private IrLoweringEngine(@NotNull IrMethod method, @NotNull MethodVisitor mv,
	                         @NotNull JvmLoweringPolicy policy,
	                         @NotNull JvmLambdaMetadata lambdaMetadata) {
		this(method, mv, policy, lambdaMetadata, JvmOptimizationFeatures.defaultFor(policy));
	}

	private IrLoweringEngine(@NotNull IrMethod method, @NotNull MethodVisitor mv,
	                         @NotNull JvmLoweringPolicy policy,
	                         @NotNull JvmLambdaMetadata lambdaMetadata,
	                         @NotNull JvmOptimizationFeatures features) {
		this.policy = policy;
		this.lambdaMetadata = lambdaMetadata;
		this.optimizationFeatures = features;
		this.instructionTracker = new InstructionTrackingMethodVisitor(
				JvmStackCheckingMethodVisitor.wrap(method.source(), mv));
		this.context = IrLoweringContext.create(method, instructionTracker);
		this.emissionState = new IrEmissionState();
		this.method = context.method();
		this.mv = context.methodVisitor();
		this.blockByOffset = context.blockByOffset();
		this.layout = new IrLoweringLayout(this.method, this::isTransparentBlock);
		this.labels = layout.labels();
		this.endLabel = layout.endLabel();
		this.operationEmitter = new IrOperationEmitter(this.mv, operationEmitterHost());
		this.emittedOps = emissionState.emittedOps();
		this.emittedEffects = emissionState.emittedEffects();
		this.registerLocalBase = IrValueEmitter.parameterSlots(method.source());
		this.analysis = IrLoweringAnalysis.analyze(method);
		this.loweringFacts = JvmLoweringFacts.analyze(method, this.analysis.useGraph());
		this.optimizationGuards = new JvmOptimizationGuards(method, this.analysis.useGraph(), loweringFacts);
		this.optimizationPlan = new JvmOptimizationPlan(method, policy, this.analysis.useGraph(), features,
				loweringFacts);
	}

	private void refreshProofFacts() {
		loweringFacts = JvmLoweringFacts.analyze(method, useGraph);
		optimizationGuards = new JvmOptimizationGuards(method, useGraph, loweringFacts);
		optimizationPlan = new JvmOptimizationPlan(method, policy, useGraph, optimizationFeatures,
				loweringFacts);
	}

	/**
	 * Emits {@code method} into an already-created ASM method visitor.
	 *
	 * @param method
	 * 		IR method to lower
	 * @param mv
	 * 		destination ASM visitor
	 */
	public static void emit(@NotNull IrMethod method, @NotNull MethodVisitor mv) {
		emitResult(method, mv, JvmLoweringPolicy.DETERMINISTIC_LOCAL);
	}

	public static void emit(@NotNull IrMethod method, @NotNull MethodVisitor mv,
	                        @NotNull JvmLoweringPolicy policy) {
		emitResult(method, mv, policy);
	}

	public static @NotNull IrLoweringResult emitResult(@NotNull IrMethod method, @NotNull MethodVisitor mv) {
		return emitResult(method, mv, JvmLoweringPolicy.DETERMINISTIC_LOCAL);
	}

	public static @NotNull IrLoweringResult emitResult(@NotNull IrMethod method, @NotNull MethodVisitor mv,
	                                                   @NotNull JvmLoweringPolicy policy) {
		return emitResult(method, mv, policy, JvmLambdaMetadata.empty());
	}

	public static @NotNull IrLoweringResult emitResult(@NotNull IrMethod method, @NotNull MethodVisitor mv,
	                                                   @NotNull JvmLoweringPolicy policy,
	                                                   @NotNull JvmLambdaMetadata lambdaMetadata) {
		return emitResult(method, mv, policy, lambdaMetadata, JvmOptimizationFeatures.defaultFor(policy));
	}

	/** Internal test/corpus hook for isolating aggressive features. */
	static @NotNull IrLoweringResult emitResult(@NotNull IrMethod method, @NotNull MethodVisitor mv,
	                                           @NotNull JvmLoweringPolicy policy,
	                                           @NotNull JvmLambdaMetadata lambdaMetadata,
	                                           @NotNull JvmOptimizationFeatures features) {
		IrLoweringEngine engine = new IrLoweringEngine(method, mv, policy, lambdaMetadata, features);
		engine.emit();
		return new IrLoweringResult(engine.tainted, engine.loweringDiagnostics);
	}

	private void emit() {
		analyzeUses();
		emittedBlockLabels.clear();
		referencedBlockTargets.clear();
		expressionPlanner.reset(true);
		// Guarded mode keeps canonical SSA values in distinct JVM locals when
		// possible. This is a presentation optimization: it gives decompilers
		// stable value identities while the verifier-safe materialization path
		// remains unchanged. Loop-counter proofs may still share their slot.
		nextLocal = JvmLocalAllocator.allocate(method, registerLocalBase, policy.guardedExpressions(),
				policy == JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
		refreshProofFacts();
		// Resource matching needs a stable label snapshot, but expression
		// candidates must see the selected lifecycle facts.  The labels are
		// refreshed once more after expression planning below; cleanup plans do
		// not retain labels as semantic state.
		initializeLabels();
		emittedOps.clear();
		emittedEffects.clear();
		collectCleanupPlans();
		collectCleanupCompositePlans();
		refreshProofFacts();
		collectReceiverChainPlans();
		collectSingleUsePlans();
		materializationPlan = JvmMaterializationPlan.from(singleUseByOperation);
		collectLoopShapePlans();
		coalesceProvenLongAccumulators();
		// Candidate planning affects whether a block still has emitted
		// statements. Allocate labels after those decisions so transparency and
		// label topology remain consistent during emission.
		initializeLabels();
		emittedOps.clear();
		emittedEffects.clear();
		// A DEX try-with-resources lowering can leave the null-resource throw in a
		// separate nested region.  Plan its relocation before labels are emitted so
		// the JVM try range covers the same source statements as the Java try block.
		collectDeferredNullThrowBlocks();
		collectHandlerStubs();
		collectHandlerTails();
		collectDeferredNormalTails();
		collectCleanupTailPlans();
		collectMonitorRegionPlans();
		collectProtectedRangePlans();
		// Exception planning can make a previously transparent block retain or
		// release emitted code.  Refresh labels after all such decisions and
		// rebuild the source-range snapshot so transparent labels cannot retain a
		// stale, distinct label that later becomes an end-of-method backedge.
		initializeLabels();
		captureLayoutTransparency();
		collectProtectedRangePlans();
		captureExceptionLayoutPlan();
		// Exception planning can turn a previously transparent block into a real
		// emitted owner. Rebuild labels from that finalized materialization state
		// before coalescing aliases; otherwise a transparent predecessor can retain
		// the owner's old shared label and force a late backward bridge.
		initializeLabels();
		captureLayoutTransparency();
		collectProtectedRangePlans();
		captureExceptionLayoutPlan();
		coalesceTransparentLabelsForDeferredBlocks();
		captureExceptionLayoutPlan();
		collectReferencedBlockTargets();
		emittedPrimaryExceptionBoundaries.clear();
		mv.visitCode();
		emitInitializedPhiValues();
		for (IrBlock block : layout.emissionOrder()) {
			// Some blocks are emitted at a different point in bytecode layout while retaining their original IR edges.
			// The deferred null-throw blocks are emitted immediately after the block that branches to them,
			// so the try range is not split before the null check.
			List<IrBlock> deferredNullThrow = exceptionLayoutPlan.deferredNullThrowInsertions(block);
			if (!deferredNullThrow.isEmpty())
				for (IrBlock deferredBlock : deferredNullThrow) {
					emitDeferredNullThrowBlock(deferredBlock);
				}
			if (exceptionLayoutPlan.skipped(block)) {
				if (emitInPlaceSkippedAlias(block)) continue;
				continue;
			}
			if (policy.aggressiveCleanup() ? layoutTransparentBlocks.contains(block) : isTransparentBlock(block))
				continue;
			currentBlock = block;
			emittedBlockLabels.add(block);
			mv.visitLabel(labels.get(block));
			if (block.exceptionValue() != null && !stubbedHandlers.contains(block)) {
				store(block.exceptionValue());
				emitPrimaryExceptionBoundaries(block);
			}
			blockEmitter.emitBody(block, blockEmitterHost());
		}
		emitDeferredNormalTails();
		emitSharedCleanupTails();
		emitSharedMonitorExitTails();
		emitSkippedBlockAliases();
		mv.visitLabel(endLabel);
		emitHandlerTails();
		emitHandlerStubs();
		emitTryCatches();
		mv.visitMaxs(0xFF, nextLocal);
	}

	private void captureExceptionLayoutPlan() {
		Map<IrBlock, Label> handlerEntries = new IdentityHashMap<>();
		for (HandlerTail tail : handlerTails.values()) handlerEntries.put(tail.root(), tail.label());
		handlerEntries.putAll(simpleHandlerStubLabels);
		Set<IrBlock> skipped = Collections.newSetFromMap(new IdentityHashMap<>());
		skipped.addAll(skippedHandlerTailBlocks);
		skipped.addAll(skippedCleanupTailBlocks);
		skipped.addAll(skippedMonitorExitBlocks);
		skipped.addAll(deferredNormalTailBlocks);
		skipped.addAll(deferredNullThrowBlocks);
		skipped.addAll(fullyInlinedReturnBlocks);
		// A direct handler entry still relies on the handler block's ordinary
		// emission to store the JVM exception into its IR value.  Do not let a
		// cleanup/inline plan skip that block and leave the handler label pointing
		// at a body whose exception local was never initialized.
		skipped.removeIf(block -> directHandlerEntries.contains(block)
				&& block.exceptionValue() != null);
		List<JvmExceptionLayoutPlan.PlannedRange> plannedRanges = prepareExceptionRanges(protectedRangePlans,
				handlerEntries, skipped);
		if (canonicalCompositePlan() != null)
			suppressUnplannedCompositeHandlers(plannedRanges, skipped);
		exceptionLayoutPlan = JvmExceptionLayoutPlan.capture(labels, handlerStubLabels,
				simpleHandlerStubLabels, handlerEntries, tryStartLabels, tryStartLabelsByBlock,
				protectedBoundaryLabels, cleanupPlans, protectedRangePlans, skipped, deferredNullThrowInsertions,
				sharedCleanupTails, sharedMonitorExitBlocks, plannedRanges);
	}

	/**
	 * A DEX nested-resource graph can retain several synthetic catch blocks for
	 * the same logical lifecycle.  Once the authoritative JVM range plan has
	 * selected the resource, failure, and finally handlers, the remaining
	 * handler-only subgraphs have no legal JVM entry.  Emitting them anyway
	 * creates unreachable ATHROW labels which CFR reconstructs as empty catches
	 * and artificial loops.  Only blocks with no ordinary predecessor are
	 * pruned; a block that is also part of normal control flow remains emitted.
	 */
	private void suppressUnplannedCompositeHandlers(
			@NotNull List<JvmExceptionLayoutPlan.PlannedRange> plannedRanges,
			@NotNull Set<IrBlock> skipped) {
		Set<IrBlock> selected = Collections.newSetFromMap(new IdentityHashMap<>());
		for (JvmExceptionLayoutPlan.PlannedRange range : plannedRanges)
			selected.add(range.handler().handlerBlock());
		Set<IrBlock> handlerRoots = Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrExceptionRegion region : method.exceptionRegions())
			for (IrExceptionHandler handler : region.handlers())
				if (!selected.contains(handler.handlerBlock())) handlerRoots.add(handler.handlerBlock());
		for (IrBlock root : handlerRoots) {
			if (!handlerOnlyBlock(root)) continue;
			ArrayDeque<IrBlock> work = new ArrayDeque<>();
			Set<IrBlock> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			work.add(root);
			while (!work.isEmpty()) {
				IrBlock block = work.removeFirst();
				if (!seen.add(block) || selected.contains(block) || !handlerOnlyBlock(block)) continue;
				skipped.add(block);
				work.addAll(block.successors());
			}
		}
	}

	private boolean handlerOnlyBlock(@NotNull IrBlock block) {
		if (block.exceptionValue() == null && block.predecessors().isEmpty()) return true;
		if (block.exceptionValue() == null && !block.predecessors().isEmpty()
				&& block.predecessors().stream().anyMatch(predecessor ->
						!predecessor.exceptionalSuccessors().contains(block))) return false;
		return block.predecessors().stream().allMatch(predecessor ->
				predecessor.exceptionalSuccessors().contains(block));
	}

	/**
	 * Resolves lifecycle metadata once during preparation.  Bytecode emission
	 * consumes this immutable snapshot; it must not rediscover a resource match
	 * after labels or handler stubs have been emitted.
	 */
	private void collectProtectedRangePlans() {
		protectedRangePlans.clear();
		protectedRangePlans.putAll(cleanupPlans);
		for (IrExceptionRegion region : method.exceptionRegions()) {
			if (protectedRangePlans.containsKey(region)) continue;
			IrBlock first = region.protectedBlocks().isEmpty() ? null : region.protectedBlocks().getFirst();
			IrBlock last = region.protectedBlocks().isEmpty() ? null : region.protectedBlocks().getLast();
			JvmCleanupRegionPlan candidate = JvmCleanupRegionPlan.match(method, region, blockByOffset,
					first == null ? null : labels.get(first), last == null ? null : labels.get(last));
			if (candidate != null) protectedRangePlans.put(region, candidate);
		}
	}

	private @NotNull List<JvmExceptionLayoutPlan.PlannedRange> prepareExceptionRanges(
			@NotNull Map<IrExceptionRegion, JvmCleanupRegionPlan> protectedRangePlans,
			@NotNull Map<IrBlock, Label> handlerEntries,
			@NotNull Set<IrBlock> skipped) {
		List<JvmExceptionLayoutPlan.PlannedRange> ranges = new ArrayList<>();
		Map<IrBlock, JvmExceptionLayoutPlan.PrimaryExceptionState> primaryStates = new IdentityHashMap<>();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			int effectiveEnd = effectiveTryCatchEndOffset(region);
			for (IrExceptionHandler exceptionHandler : region.handlers()) {
				boolean synthetic = isSyntheticRethrowRegion(region, exceptionHandler);
				boolean redundant = isRedundantNullResourceRegion(region, exceptionHandler);
				if (synthetic || redundant) {
					continue;
				}
				List<IrBlock> sources = coveredSourceBlocks(region, exceptionHandler);
				if (sources.isEmpty()) {
					report(ConversionDiagnostic.Kind.VERIFIER, region.startOffset(),
							"Suppressed an exception range with no source blocks");
					continue;
				}
				List<IrBlock> ordered = new ArrayList<>(sources);
				ordered.sort(Comparator.comparingInt(block -> layout.emissionOrder().indexOf(block)));
				Handler handler = exceptionHandler.handler();
				String catchType = handler == null || handler.isCatchAll() ? null : handler.exceptionType().internalName();
				JvmCleanupRegionPlan lifecycle = lifecycleForRange(region);
				JvmExceptionLayoutPlan.PrimaryExceptionState primaryExceptionState =
						primaryExceptionState(lifecycle, primaryStates, skipped);
				JvmCleanupCompositePlan composite = cleanupCompositeByRegion.get(region);
				JvmCleanupHandlerRole handlerRole = composite == null ? null : composite.handlerRoles().get(region);
				boolean requiresStubs = exceptionLayoutPlanNeedsStubs(ordered, exceptionHandler.handlerBlock());
				if (!requiresStubs) {
					Label handlerLabel = plannedHandlerLabel(ordered.getFirst(), exceptionHandler.handlerBlock(), handlerEntries);
					Label end = labelAtOrEnd(effectiveEnd);
					ranges.add(new JvmExceptionLayoutPlan.PlannedRange(region, exceptionHandler,
							lifecycle, ordered.getFirst(), ordered.getLast(),
							end, handlerLabel, catchType, handlerRole, false,
							tryStartLabels.containsKey(region), ordered.size() > 1, false,
							primaryExceptionState));
					continue;
				}
				IrBlock first = null;
				IrBlock previous = null;
				Label activeHandler = null;
				for (IrBlock source : ordered) {
					Label sourceHandler = plannedHandlerLabel(source, exceptionHandler.handlerBlock(), handlerEntries);
					if (first != null && sourceHandler == activeHandler && previous != null
							&& canCoalesceProtectedRange(region, previous, source)) {
						previous = source;
						continue;
					}
					if (first != null)
						ranges.add(new JvmExceptionLayoutPlan.PlannedRange(region, exceptionHandler,
								lifecycle, first, previous,
								protectedEndLabel(previous, effectiveEnd),
								activeHandler, catchType, handlerRole, true, tryStartLabels.containsKey(region), first != previous, false,
								primaryExceptionState));
					first = source;
					previous = source;
					activeHandler = sourceHandler;
				}
				if (first != null)
					ranges.add(new JvmExceptionLayoutPlan.PlannedRange(region, exceptionHandler,
							lifecycle, first, previous,
							protectedEndLabel(previous, effectiveEnd),
							activeHandler, catchType, handlerRole, true, tryStartLabels.containsKey(region), first != previous, false,
							primaryExceptionState));
			}
		}
		JvmCleanupCompositePlan composite = canonicalCompositePlan();
		if (composite != null) {
			List<JvmExceptionLayoutPlan.PlannedRange> canonical = prepareCanonicalCompositeRanges(
					composite, handlerEntries, skipped);
		if (canonical != null) {
				// The composite resource envelope replaces DEX-split body ranges,
				// but it must not replace the nested close-failure handlers.  Those
				// handlers carry the explicit addSuppressed operation which gives
				// the JVM bytecode the same observable suppression semantics as the
				// source try-with-resources lowering.  Retain only those original
				// ranges; ordinary duplicate body handlers remain coalesced into the
				// canonical envelope.
				List<JvmExceptionLayoutPlan.PlannedRange> suppression = ranges.stream()
						.filter(range -> hasSuppressedEffect(range.handler().handlerBlock()))
						.toList();
				if (suppression.isEmpty()) return canonical;
				List<JvmExceptionLayoutPlan.PlannedRange> combined = new ArrayList<>(canonical);
				for (JvmExceptionLayoutPlan.PlannedRange candidate : suppression) {
					boolean duplicate = combined.stream().anyMatch(existing ->
							existing.handler().handlerBlock() == candidate.handler().handlerBlock()
								&& existing.firstSource() == candidate.firstSource()
								&& existing.lastSource() == candidate.lastSource());
					if (!duplicate) combined.add(candidate);
				}
				return List.copyOf(combined);
		}
		}
		appendCompositeFailureEnvelopes(ranges, protectedRangePlans, handlerEntries);
		return List.copyOf(ranges);
	}

	private boolean hasSuppressedEffect(@NotNull IrBlock handler) {
		return reachableBlocks(handler).stream().anyMatch(block -> block.statements().stream()
				.anyMatch(statement -> switch (statement) {
					case IrOp op -> op.payload() instanceof InvokeInstruction invoke
							&& "addSuppressed".equals(invoke.name());
					case IrEffect effect -> effect.payload() instanceof InvokeInstruction invoke
							&& "addSuppressed".equals(invoke.name());
					default -> false;
				}));
	}

	/**
	 * Converts the DEX-split fragments of a proven nested resource lifecycle to
	 * the small set of JVM ranges that try-with-resources requires.  This is a
	 * lowering-only layout decision: the SSA graph and its individual handler
	 * blocks remain unchanged, but unselected fragment handlers are no longer
	 * advertised as independent JVM catches.
	 */
	private @Nullable List<JvmExceptionLayoutPlan.PlannedRange> prepareCanonicalCompositeRanges(
			@NotNull JvmCleanupCompositePlan composite,
			@NotNull Map<IrBlock, Label> handlerEntries,
			@NotNull Set<IrBlock> skipped) {
	IrBlock firstSource = firstCompositeSource(composite, skipped);
	if (firstSource == null) return null;
	List<JvmExceptionLayoutPlan.PlannedRange> result = new ArrayList<>();
	IrBlock lastClose = null;
	int lastCloseOffset = Integer.MIN_VALUE;
	for (JvmCleanupRegionPlan layer : composite.layers()) {
		IrBlock closeBlock = blockContaining(layer.normalClose());
		if (closeBlock == null) return null;
		// The DEX frontend commonly puts the allocation, its input-producing
		// operation, and the constructor in one basic block.  The protected JVM
		// range must begin at that block, not at the following block merely
		// because the acquisition is not its final statement.  The composite
		// proof has already established source order and resource containment;
		// using the containing block preserves those facts without fragmenting
		// the lifecycle back into DEX-sized ranges.
		IrBlock acquisitionBlock = blockContaining(layer.acquisition());
		IrBlock startBlock = layer.acquisition() == null
				? firstSource : acquisitionBlock;
		if (startBlock == null || skipped.contains(startBlock)) return null;
		Label start = labels.get(startBlock);
		Label end = labels.get(closeBlock);
		Label handlerLabel = plannedHandlerLabel(startBlock, layer.handler().handlerBlock(), handlerEntries);
		if (start == null || end == null || handlerLabel == null || start == end) return null;
		result.add(new JvmExceptionLayoutPlan.PlannedRange(layer.region(), layer.handler(), layer,
				startBlock, closeBlock, end, handlerLabel, catchType(layer.handler()),
				JvmCleanupHandlerRole.RESOURCE_CLOSE, true, false, true, false, null));
		int closeOffset = layer.normalCloseOffset(method);
		if (closeOffset >= lastCloseOffset) {
			lastClose = closeBlock;
			lastCloseOffset = closeOffset;
		}

		IrExceptionEdge closeEdge = layer.closeException();
		if (closeEdge != null && closeEdge.handlerBlock() != layer.handler().handlerBlock()) {
			IrExceptionHandler closeHandler = exceptionHandlerFor(closeEdge);
			IrBlock closeEndBlock = firstEmittedBlockAfter(closeBlock, skipped);
			IrExceptionRegion closeRegion = closeHandler == null ? null : handlerRegion(closeHandler);
			if (closeHandler != null && closeRegion != null && closeEndBlock != null) {
				Label closeHandlerLabel = plannedHandlerLabel(closeBlock, closeHandler.handlerBlock(), handlerEntries);
				Label closeEnd = labels.get(closeEndBlock);
				if (closeHandlerLabel != null && closeEnd != null && labels.get(closeBlock) != closeEnd)
					result.add(new JvmExceptionLayoutPlan.PlannedRange(closeRegion, closeHandler,
							layer, closeBlock, closeEndBlock, closeEnd, closeHandlerLabel, catchType(closeHandler),
							JvmCleanupHandlerRole.SUPPRESSED_RETHROW, true, false, true, false, null));
			}
		}
	}
	if (lastClose == null) return null;
	IrBlock outerEndBlock = normalContinuation(lastClose, skipped);
	Label outerEnd = outerEndBlock == null ? endLabel : labels.get(outerEndBlock);
	if (outerEnd == null) return null;
	IrExceptionHandler failure = compositeOuterHandler(composite, true);
	IrExceptionHandler cleanup = compositeOuterHandler(composite, false);
	if (failure == null || cleanup == null) return null;
	Label failureLabel = plannedHandlerLabel(firstSource, failure.handlerBlock(), handlerEntries);
	Label cleanupLabel = plannedHandlerLabel(firstSource, cleanup.handlerBlock(), handlerEntries);
	if (failureLabel == null || cleanupLabel == null) return null;
	IrExceptionRegion failureRegion = handlerRegion(failure);
	IrExceptionRegion cleanupRegion = handlerRegion(cleanup);
	if (failureRegion == null || cleanupRegion == null) return null;
	result.add(new JvmExceptionLayoutPlan.PlannedRange(failureRegion, failure, composite.outer(),
			firstSource, outerEndBlock == null ? firstSource : outerEndBlock, outerEnd, failureLabel, null,
			JvmCleanupHandlerRole.OUTER_FAILURE, true, false, true, false, null));
	result.add(new JvmExceptionLayoutPlan.PlannedRange(cleanupRegion, cleanup, composite.outer(),
			firstSource, outerEndBlock == null ? firstSource : outerEndBlock, outerEnd, cleanupLabel, null,
			JvmCleanupHandlerRole.FINALLY_CLEANUP, true, false, true, false, null));
	IrBlock failureEndBlock = normalContinuation(failure.handlerBlock(), skipped);
	Label failureEnd = failureEndBlock == null ? outerEnd : labels.get(failureEndBlock);
	if (failureEnd != null && failureEnd != labels.get(failure.handlerBlock())) {
		result.add(new JvmExceptionLayoutPlan.PlannedRange(cleanupRegion, cleanup, composite.outer(),
				failure.handlerBlock(), failureEndBlock == null ? failure.handlerBlock() : failureEndBlock,
				failureEnd, cleanupLabel, null, JvmCleanupHandlerRole.FINALLY_CLEANUP,
				true, false, true, false, null));
	}
	return List.copyOf(result);
}

	private @Nullable JvmCleanupCompositePlan canonicalCompositePlan() {
	if (!policy.aggressiveCleanup()) return null;
	return cleanupCompositePlans.stream()
			.filter(plan -> plan.accepted() && plan.layers().size() >= 3)
			.min(Comparator.comparingInt((JvmCleanupCompositePlan plan) -> plan.outer().region().startOffset()))
			.orElse(null);
}

	private @Nullable IrBlock firstCompositeSource(@NotNull JvmCleanupCompositePlan composite,
	                                               @NotNull Set<IrBlock> skipped) {
	return composite.layers().stream().flatMap(plan -> plan.protectedBody().stream())
			.filter(block -> !skipped.contains(block)
					&& !(policy.aggressiveCleanup() && layoutTransparentBlocks.contains(block)))
			.min(Comparator.comparingInt(block -> layout.emissionOrder().indexOf(block))).orElse(null);
}

	private @Nullable IrBlock firstEmittedBlockAfter(@Nullable IrBlock block, @NotNull Set<IrBlock> skipped) {
	if (block == null) return null;
	int index = layout.emissionOrder().indexOf(block);
	if (index < 0) return null;
	for (int i = index + 1; i < layout.emissionOrder().size(); i++) {
		IrBlock candidate = layout.emissionOrder().get(i);
		if (!skipped.contains(candidate)
				&& !(policy.aggressiveCleanup() && layoutTransparentBlocks.contains(candidate))) return candidate;
	}
	return null;
}

	private @Nullable IrBlock normalContinuation(@NotNull IrBlock block, @NotNull Set<IrBlock> skipped) {
	for (IrBlock successor : block.successors())
		if (!block.exceptionalSuccessors().contains(successor)) {
			IrBlock emitted = successor;
			while (emitted != null && (skipped.contains(emitted)
					|| (policy.aggressiveCleanup() && layoutTransparentBlocks.contains(emitted)))) {
				if (emitted.successors().size() != 1) break;
				emitted = emitted.successors().getFirst();
			}
			if (emitted != null) return emitted;
		}
	return firstEmittedBlockAfter(block, skipped);
}

	private @Nullable IrExceptionHandler exceptionHandlerFor(@NotNull IrExceptionEdge edge) {
	for (IrExceptionRegion region : method.exceptionRegions())
		for (IrExceptionHandler handler : region.handlers())
			if (handler.handlerBlock() == edge.handlerBlock()
					&& (edge.handler() == null || handler.handler() == edge.handler())) return handler;
	return null;
}

	private @Nullable IrExceptionRegion handlerRegion(@NotNull IrExceptionHandler wanted) {
	for (IrExceptionRegion region : method.exceptionRegions())
		if (region.handlers().contains(wanted)) return region;
	return null;
}

	private @Nullable String catchType(@NotNull IrExceptionHandler handler) {
	return handler.handler() == null || handler.handler().isCatchAll()
			? null : handler.handler().exceptionType().internalName();
}

	private @Nullable IrExceptionHandler compositeOuterHandler(@NotNull JvmCleanupCompositePlan composite,
	                                                          boolean failure) {
	Set<IrBlock> resources = Collections.newSetFromMap(new IdentityHashMap<>());
	for (JvmCleanupRegionPlan layer : composite.layers()) resources.add(layer.handler().handlerBlock());
	// A close-failure/suppression handler is a nested lifecycle handler, not
	// the outer body-failure entry.  Reusing it for the outer envelope creates
	// overlapping ranges with one handler label; ASM then fragments the ranges
	// during frame computation and CFR reconstructs repeated catch routes.
	for (JvmCleanupRegionPlan layer : composite.layers()) {
		IrExceptionEdge closeException = layer.closeException();
		if (closeException != null) resources.add(closeException.handlerBlock());
	}

	List<IrExceptionHandler> candidates = new ArrayList<>();
	for (IrExceptionRegion region : method.exceptionRegions())
		for (IrExceptionHandler candidate : region.handlers()) {
			if (candidate.handler() == null || !candidate.handler().isCatchAll()
					|| resources.contains(candidate.handlerBlock()) || candidate.handlerBlock().exceptionValue() == null) continue;
			Set<IrBlock> reachable = reachableBlocks(candidate.handlerBlock());
			boolean closes = reachable.stream().flatMap(block -> block.statements().stream())
					.filter(IrOp.class::isInstance).map(IrOp.class::cast)
					.anyMatch(op -> op.payload() instanceof InvokeInstruction invoke && "close".equals(invoke.name()));
			if (closes) continue;
			boolean returns = reachable.stream().anyMatch(block -> block.terminator() != null
					&& block.terminator().kind() == IrTerminatorKind.RETURN);
		boolean throwsPath = reachable.stream().anyMatch(block -> block.terminator() != null
					&& block.terminator().kind() == IrTerminatorKind.THROW);
			if (failure == returns && (!failure || !throwsPath)) candidates.add(candidate);
		}
	if (candidates.isEmpty()) return null;
	return candidates.stream().reduce((left, right) -> failure
			? (left.handlerBlock().startOffset() <= right.handlerBlock().startOffset() ? left : right)
			: (left.handlerBlock().startOffset() >= right.handlerBlock().startOffset() ? left : right)).orElse(null);
}

	/**
	 * A DEX frontend may encode the outer catch/finally as a tiny trailing
	 * region even though its handler is the continuation for the complete
	 * resource body.  Once a nested lifecycle has been proven, add the JVM
	 * envelope around the complete body.  Inner resource ranges remain earlier
	 * in table order and therefore retain close/suppression priority.
	 */
	private void appendCompositeFailureEnvelopes(
			@NotNull List<JvmExceptionLayoutPlan.PlannedRange> ranges,
			@NotNull Map<IrExceptionRegion, JvmCleanupRegionPlan> protectedRangePlans,
			@NotNull Map<IrBlock, Label> handlerEntries) {
	if (!policy.aggressiveCleanup() || cleanupCompositePlans.isEmpty()) return;
	JvmCleanupCompositePlan composite = cleanupCompositePlans.stream()
			.filter(JvmCleanupCompositePlan::accepted)
			.min(Comparator.comparingInt((JvmCleanupCompositePlan plan) -> plan.outer().region().startOffset())
					.thenComparingInt(plan -> plan.outer().region().endOffset()))
			.orElse(null);
	if (composite == null) return;
	IrBlock firstSource = composite.layers().stream()
			.flatMap(layer -> layer.protectedBody().stream())
			.min(Comparator.comparingInt(block -> layout.emissionOrder().indexOf(block)))
			.orElse(null);
	if (firstSource == null) return;

	Set<IrBlock> resourceHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
	for (JvmCleanupRegionPlan layer : composite.layers())
		resourceHandlers.add(layer.handler().handlerBlock());
	List<IrExceptionHandler> failureHandlers = new ArrayList<>();
	List<IrExceptionHandler> finallyHandlers = new ArrayList<>();
	for (IrExceptionRegion region : method.exceptionRegions()) {
		for (IrExceptionHandler candidate : region.handlers()) {
			if (candidate.handler() == null || !candidate.handler().isCatchAll()
					|| resourceHandlers.contains(candidate.handlerBlock())
					|| candidate.handlerBlock().exceptionValue() == null) continue;
			Set<IrBlock> reachable = reachableBlocks(candidate.handlerBlock());
			boolean closesResource = reachable.stream().flatMap(block -> block.statements().stream())
					.filter(IrOp.class::isInstance).map(IrOp.class::cast)
					.anyMatch(op -> op.payload() instanceof InvokeInstruction invoke
							&& "close".equals(invoke.name()));
			if (closesResource) continue;
			boolean returns = reachable.stream().anyMatch(block -> block.terminator() != null
					&& block.terminator().kind() == IrTerminatorKind.RETURN);
			boolean throwsPath = reachable.stream().anyMatch(block -> block.terminator() != null
					&& block.terminator().kind() == IrTerminatorKind.THROW);
			if (returns) failureHandlers.add(candidate);
			else if (throwsPath) finallyHandlers.add(candidate);
		}
	}
	if (failureHandlers.isEmpty() && finallyHandlers.isEmpty()) return;
	if (failureHandlers.size() > 1)
		failureHandlers = List.of(failureHandlers.stream()
				.min(Comparator.comparingInt(handler -> handler.handlerBlock().startOffset())).orElseThrow());
	if (finallyHandlers.size() > 1)
		finallyHandlers = List.of(finallyHandlers.stream()
				.max(Comparator.comparingInt(handler -> handler.handlerBlock().startOffset())).orElseThrow());
	appendCompositeEnvelopeRanges(ranges, failureHandlers, firstSource, composite, handlerEntries,
			JvmCleanupHandlerRole.OUTER_FAILURE);
	appendCompositeEnvelopeRanges(ranges, finallyHandlers, firstSource, composite, handlerEntries,
			JvmCleanupHandlerRole.FINALLY_CLEANUP);
}

	private void appendCompositeEnvelopeRanges(
			@NotNull List<JvmExceptionLayoutPlan.PlannedRange> ranges,
			@NotNull List<IrExceptionHandler> handlers,
			@NotNull IrBlock firstSource,
			@NotNull JvmCleanupCompositePlan composite,
			@NotNull Map<IrBlock, Label> handlerEntries,
			@NotNull JvmCleanupHandlerRole role) {
	Set<IrBlock> seen = Collections.newSetFromMap(new IdentityHashMap<>());
	for (IrExceptionHandler handler : handlers) {
		if (!seen.add(handler.handlerBlock())) continue;
		IrExceptionRegion sourceRegion = method.exceptionRegions().stream()
				.filter(region -> region.handlers().contains(handler)).findFirst().orElse(null);
		if (sourceRegion == null) continue;
		Label label = plannedHandlerLabel(firstSource, handler.handlerBlock(), handlerEntries);
		if (label == null) continue;
		if (ranges.stream().anyMatch(range -> range.handler().handlerBlock() == handler.handlerBlock()
				&& range.firstSource() == firstSource && range.end() == endLabel)) continue;
		// Replace the narrow DEX fragment for this same handler rather than
		// adding a second overlapping JVM entry.  Inner resource entries remain
		// untouched and retain their table priority.
		ranges.removeIf(range -> range.handler().handlerBlock() == handler.handlerBlock());
		ranges.add(new JvmExceptionLayoutPlan.PlannedRange(
				sourceRegion,
				handler, composite.outer(), firstSource, firstSource, endLabel, label, null, role,
				true, false, true, false, null));
	}
}

	private @Nullable JvmExceptionLayoutPlan.PrimaryExceptionState primaryExceptionState(
			@Nullable JvmCleanupRegionPlan lifecycle,
			@NotNull Map<IrBlock, JvmExceptionLayoutPlan.PrimaryExceptionState> states,
			@NotNull Set<IrBlock> skipped) {
		if (!policy.aggressiveCleanup() || lifecycle == null || !lifecycle.hasSuppressedExceptionPath()) return null;
		IrValue primary = lifecycle.primaryException();
		if (primary == null || !primary.hasLocal() || primary.isUnknown() || primary.isImprecise()
				|| primary.stackOnly()) return null;
		IrValue canonical = primary.canonical();
		IrBlock entry = method.blocks().stream()
				.filter(block -> block.exceptionValue() != null
						&& block.exceptionValue().canonical() == canonical)
				.findFirst().orElse(null);
		if (entry == null || skipped.contains(entry) || stubbedHandlers.contains(entry)
				|| handlerTails.values().stream().anyMatch(tail -> tail.root() == entry)) return null;
		IrBlock closeBlock = method.blocks().stream()
				.filter(block -> block.statements().contains(lifecycle.normalClose()))
				.findFirst().orElse(null);
		if (closeBlock == null || !dominates(entry, closeBlock)) return null;
		return states.computeIfAbsent(entry, block -> new JvmExceptionLayoutPlan.PrimaryExceptionState(
				canonical, block, canonical.local(), new Label()));
	}

	private @Nullable JvmCleanupRegionPlan lifecycleForRange(@NotNull IrExceptionRegion region) {
		if (policy.aggressiveCleanup()) {
			JvmCleanupCompositePlan composite = cleanupCompositeByRegion.get(region);
			if (composite != null && composite.accepted()) {
				// A composite is a containment proof, not a replacement for the
				// lifecycle facts of every nested region.  Protected-boundary and
				// null-resource decisions must use the resource owned by the actual
				// source region; using the outer resource here can silently move an
				// inner range to the wrong acquisition boundary.
				for (JvmCleanupRegionPlan layer : composite.layers())
					if (layer.region() == region) return layer;
				return composite.outer();
			}
		}
		return protectedRangePlans.get(region);
	}

	private boolean exceptionLayoutPlanNeedsStubs(@NotNull List<IrBlock> sources, @NotNull IrBlock target) {
		return simpleHandlerStubLabels.containsKey(target)
				|| sources.stream().anyMatch(source -> handlerStubLabels.containsKey(new JvmHandlerStubKey(source, target)));
	}

	private @NotNull Label plannedHandlerLabel(@NotNull IrBlock source, @NotNull IrBlock target,
	                                           @NotNull Map<IrBlock, Label> handlerEntries) {
		Label stub = handlerStubLabels.get(new JvmHandlerStubKey(source, target));
		if (stub != null) return stub;
		Label entry = handlerEntries.get(target);
		if (entry != null) return entry;
		return labels.get(target);
	}

	private boolean isIoCatch(@Nullable Handler handler) {
		if (handler == null || handler.isCatchAll() || handler.exceptionType() == null) return false;
		try {
			Class<?> caught = Class.forName(handler.exceptionType().internalName().replace('/', '.'), false,
					IrLoweringEngine.class.getClassLoader());
			return IOException.class.isAssignableFrom(caught);
		} catch (LinkageError | ClassNotFoundException ignored) {
			return false;
		}
	}

	private @NotNull IrBlockEmitter.Host blockEmitterHost() {
		return new IrBlockEmitter.Host() {
			@Override
			public void beginOperandStackCarry(@NotNull IrBlock block) {
				if (policy.optimized())
					IrLoweringEngine.this.beginOperandStackCarry(block);
			}

			@Override
			public boolean wasEffectEmitted(@NotNull IrEffect effect) {
				return emittedEffects.contains(effect);
			}

			@Override
			public boolean tryCarryInvokeInput(@NotNull IrBlock block, @NotNull List<IrStmt> statements,
			                                   int index, IrTerminator blockTerminator) {
				if (!policy.optimized()) return false;
				return IrLoweringEngine.this.tryCarryInvokeInput(block, statements, index, blockTerminator);
			}

			@Override
			public boolean isDirectPhiReturnOperand(@NotNull IrBlock block, @NotNull IrStmt statement) {
				if (!policy.optimized()) return false;
				return IrLoweringEngine.this.isDirectPhiReturnOperand(block, statement);
			}

			@Override
			public boolean shouldSkipSeparateEmission(@NotNull List<IrStmt> statements, int index,
			                                          IrTerminator blockTerminator) {
				if (!policy.optimized()) return false;
				return IrLoweringEngine.this.shouldSkipSeparateEmission(statements, index, blockTerminator);
			}

			@Override
			public int emitSpecialChain(@NotNull List<IrStmt> statements, int index) {
				if (!policy.optimized()) return 0;
				return IrLoweringEngine.this.emitSpecialChain(statements, index);
			}

			@Override
			public void setCurrentStatement(IrStmt statement) {
				currentStatement = statement;
			}

			@Override
			public void emitStatement(@NotNull IrStmt statement) {
				IrLoweringEngine.this.emitStatement(statement);
			}

			@Override
			public void beforeTerminator(@NotNull IrBlock block) {
				// The custom label must remain immediately before the branch so the
				// branch's null-throw path stays inside the outer protected range.
				Label tryStart = tryStartLabelsByBlock.get(block);
				if (tryStart != null) mv.visitLabel(tryStart);
			}

			@Override
			public void markOperationEmitted(@NotNull IrOp op) {
				emittedOps.add(op);
			}

			@Override
			public void emitTerminator(@NotNull IrBlock block) {
				IrLoweringEngine.this.emitTerminator(block);
			}

			@Override
			public boolean hasUnconsumedOperandStackCarry(@NotNull IrBlock block) {
				if (!policy.optimized()) return false;
				return IrLoweringEngine.this.hasUnconsumedOperandStackCarry(block);
			}

			@Override
			public void clearOperandStackCarry() {
				if (policy.optimized())
					IrLoweringEngine.this.clearOperandStackCarry();
			}
		};
	}

	private @NotNull IrOperationEmitter.Host operationEmitterHost() {
		return new IrOperationEmitter.Host() {
			@Override
			public void load(@NotNull IrValue value, @NotNull ClassType expectedType) {
				IrLoweringEngine.this.load(value, expectedType);
			}

			@Override
			public void store(@NotNull IrValue value) {
				IrLoweringEngine.this.store(value);
			}

			@Override
			public boolean tryEmitIncrement(@NotNull IrOp op, int constant,
			                               @NotNull IrOperationEmitter.ResultMode resultMode) {
				return IrLoweringEngine.this.tryEmitIncrement(op, constant, resultMode);
			}

			@Override
			public boolean tryEmitLongIncrement(@NotNull IrOp op,
			                                  @NotNull IrOperationEmitter.ResultMode resultMode) {
				return IrLoweringEngine.this.tryEmitLongIncrement(op, resultMode);
			}

			@Override
			public boolean shouldKeepConstructedInstance(@NotNull IrOp newInstanceOp) {
				return IrLoweringEngine.this.shouldKeepConstructedInstance(newInstanceOp);
			}

			@Override
			public boolean isOperationEmitted(@NotNull IrOp op) {
				return emittedOps.contains(op);
			}

			@Override
			public boolean tryEmitSyntheticLambda(@NotNull IrOp consumer,
			                                      @NotNull InvokeInstruction instruction,
			                                      int inputIndex) {
				return IrLoweringEngine.this.tryEmitSyntheticLambda(consumer, instruction, inputIndex);
			}
		};
	}

	private void collectHandlerTails() {
		// A handler that only stores the caught exception and jumps to a terminal
		// throw is emitted as one shared tail.  Keeping that bookkeeping out of the
		// normal block order avoids duplicate ATHROW paths in the class file.
		handlerTails.clear();
		skippedHandlerTailBlocks.clear();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			for (IrExceptionHandler handler : region.handlers()) {
				IrBlock root = handler.handlerBlock();
				if (handlerTails.containsKey(root) || root.exceptionValue() == null
						|| hasHandlerStubs(root)) continue;
				IrBlock target = handlerTailTarget(root);
				if (target == null) continue;
				HandlerTail tail = new HandlerTail(root, target, new Label());
				handlerTails.put(root, tail);
				skippedHandlerTailBlocks.add(root);
				skippedHandlerTailBlocks.add(target);
			}
		}
	}

	private void collectDeferredNormalTails() {
		// Exception-boundary glue can otherwise make a normal return appear to be
		// part of the handler. Such a unique return tail is emitted after the main
		// block order, where decompilers can recognize it as the method's normal exit.
		deferredNormalTailBlocks.clear();
		// A proven composite resource lifecycle already has an explicit normal
		// cleanup continuation in its main CFG.  Deferring that continuation past
		// the handler stubs creates an artificial break around the whole try body;
		// keep the normal fall-through in place and defer only exceptional tails.
		if (policy.aggressiveCleanup() && cleanupCompositePlans.stream()
				.anyMatch(plan -> plan.accepted() && plan.layers().size() >= 3)) return;
		Set<IrBlock> handlerBlocks = new HashSet<>();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			for (IrExceptionHandler handler : region.handlers())
				handlerBlocks.add(handler.handlerBlock());
		}
		for (IrBlock block : method.blocks()) {
			if (!isExceptionBoundaryGlue(block)) continue;
			IrTerminator terminator = block.terminator();
			if (terminator == null || terminator.kind() != IrTerminatorKind.GOTO) continue;
			IrBlock target = gotoTarget(block, terminator.payload());
			if (target == null || handlerBlocks.contains(target) || target.exceptionValue() != null
					|| !target.exceptionalSuccessors().isEmpty() || target.terminator() == null
					|| target.terminator().kind() != IrTerminatorKind.RETURN
					|| target.predecessors().size() != 1
					|| target.predecessors().stream().anyMatch(handlerBlocks::contains)
					|| target.predecessors().stream().filter(this::isExceptionBoundaryGlue).count() != 1) continue;
			deferredNormalTailBlocks.add(target);
		}
	}

	private void collectCleanupPlans() {
		cleanupPlans.clear();
		if (!policy.aggressiveCleanup()
				|| !optimizationPlan.featureEnabled(JvmOptimizationFeature.CLEANUP_REGIONS)) return;
		List<JvmCleanupRegionPlan> selected = new ArrayList<>();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			IrBlock first = region.protectedBlocks().isEmpty() ? null : region.protectedBlocks().getFirst();
			IrBlock last = region.protectedBlocks().isEmpty() ? null : region.protectedBlocks().getLast();
			JvmCleanupRegionPlan candidate = JvmCleanupRegionPlan.match(method, region, blockByOffset,
					first == null ? null : labels.get(first), last == null ? null : labels.get(last));
			if (candidate == null) continue;
			JvmOptimizationDecision decision = optimizationPlan.resourceRegion(region, candidate);
			if (policy.optimized() && !decision.accepted()) continue;
			if (decision.relaxed())
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
						"Applied aggressive resource lifecycle plan: " + decision.reason());
			selected.add(candidate.withDecision(decision));
		}
		for (JvmCleanupRegionPlan plan : selected) {
			List<JvmCleanupRegionPlan> nested = selected.stream()
					.filter(child -> child != plan
							&& child.region().startOffset() >= plan.region().startOffset()
							&& child.region().endOffset() <= plan.region().endOffset())
					.toList();
			// Keep only a proven layer composition on the parent.  The child
			// plans remain independently selectable when containment or resource
			// identity is ambiguous; this prevents an outer cleanup tail from
			// accidentally consuming an inner handler state.
			JvmCleanupRegionPlan composed = plan.withNested(nested);
			if (!composed.nestedLayerProof(method)) {
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, plan.region().startOffset(),
						ConversionDiagnostic.Severity.INFO, false,
						"Rejected nested resource composition: containment or reverse-close proof is incomplete");
				composed = plan.withNested(List.of());
			}
			cleanupPlans.put(plan.region(), composed);
		}
	}

	/**
	 * Selects complete nested lifecycles before handler labels are allocated.
	 * A partial composition is intentionally not used: each layer continues
	 * through the ordinary local-materialized path instead.
	 */
	private void collectCleanupCompositePlans() {
		cleanupCompositePlans.clear();
		cleanupCompositeByRegion.clear();
		if (!policy.aggressiveCleanup()
				|| !optimizationPlan.featureEnabled(JvmOptimizationFeature.CLEANUP_REGIONS)) return;
		// DEX protected intervals are frequently adjacent fragments of one
		// nested resource scope.  Compose the distinct resource lifecycles by
		// acquisition/reverse-close order before falling back to literal region
		// containment.
		if (cleanupPlans.size() > 1) {
			JvmCleanupCompositePlan ordered = JvmCleanupCompositePlan.discoverOrdered(method,
					new ArrayList<>(cleanupPlans.values()));
			if (ordered.accepted()) {
				cleanupCompositePlans.add(ordered);
				for (JvmCleanupRegionPlan layer : ordered.layers())
					cleanupCompositeByRegion.put(layer.region(), ordered);
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, ordered.outer().region().startOffset(),
						"Applied aggressive ordered nested-resource composition: " + ordered.reason());
			}
		}
		for (JvmCleanupRegionPlan plan : cleanupPlans.values()) {
			if (plan.nestedPlans().isEmpty()) continue;
			JvmCleanupCompositePlan composite = JvmCleanupCompositePlan.discover(method, plan);
			if (!composite.accepted()) {
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, plan.region().startOffset(),
						ConversionDiagnostic.Severity.INFO, false,
						"Rejected nested resource composition: " + composite.reason());
				continue;
			}
			cleanupCompositePlans.add(composite);
			for (JvmCleanupRegionPlan layer : composite.layers())
				cleanupCompositeByRegion.put(layer.region(), composite);
			report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, plan.region().startOffset(),
					"Applied aggressive nested-resource composition: " + composite.reason());
		}
	}

	private static @NotNull Set<IrBlock> reachableBlocks(@NotNull IrBlock root) {
		Set<IrBlock> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<IrBlock> work = new ArrayDeque<>();
		work.add(root);
		while (!work.isEmpty()) {
			IrBlock block = work.removeFirst();
			if (visited.add(block)) work.addAll(block.successors());
		}
		return visited;
	}

	/**
	 * Finds identical terminal cleanup blocks without changing the IR graph.  A
	 * first lowering slice intentionally accepts only single-block, non-throwing
	 * tails.  This is enough to share the common remove/delete/rethrow glue while
	 * keeping protected close operations in their original exception ranges.
	 */
	private void collectCleanupTailPlans() {
		cleanupTailPlans.clear();
		sharedCleanupTails.clear();
		cleanupTailLabels.clear();
		skippedCleanupTailBlocks.clear();
		if (!policy.aggressiveCleanup()
				|| !optimizationPlan.featureEnabled(JvmOptimizationFeature.CLEANUP_TAILS)) return;

		Map<JvmCleanupTailSignature, List<JvmCleanupTailCandidate>> groups = new LinkedHashMap<>();
		for (IrBlock block : method.blocks()) {
			if (skippedHandlerTailBlocks.contains(block) || deferredNormalTailBlocks.contains(block)
					|| deferredNullThrowBlocks.contains(block)) continue;
			JvmCleanupTailCandidate candidate = cleanupTailCandidate(block);
			if (candidate != null)
				groups.computeIfAbsent(candidate.signature(), ignored -> new ArrayList<>()).add(candidate);
		}

		for (List<JvmCleanupTailCandidate> candidates : groups.values()) {
			if (candidates.size() < 2) continue;
			JvmCleanupTailCandidate canonical = candidates.getFirst();
			List<JvmCleanupTailCandidate> duplicates = List.copyOf(candidates.subList(1, candidates.size()));
			boolean proof = cleanupTailProof(canonical, duplicates);
			JvmOptimizationDecision decision = optimizationPlan.cleanupTail(canonical, proof);
			if (!decision.accepted()) {
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
						ConversionDiagnostic.Severity.INFO, false,
						"Rejected cleanup-tail normalization: " + decision.reason());
				continue;
			}

			Map<IrBlock, Map<IrValue, IrValue>> mappings = new LinkedHashMap<>();
			for (JvmCleanupTailCandidate duplicate : duplicates)
				mappings.put(duplicate.entry(), cleanupTailValueMapping(canonical, duplicate));
			JvmCleanupTailPlan plan = new JvmCleanupTailPlan(canonical.signature(), canonical, duplicates,
					mappings, decision);
			cleanupTailPlans.add(plan);
			cleanupTailLabels.put(plan, new Label());
			for (JvmCleanupTailCandidate candidate : candidates) {
				sharedCleanupTails.put(candidate.entry(), plan);
				skippedCleanupTailBlocks.addAll(candidate.tailBlocks());
			}
			report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
					"Applied aggressive cleanup-tail normalization: " + decision.reason());
		}
	}

	/**
	 * Plans the only monitor transformation currently permitted by aggressive
	 * lowering: equivalent monitor-exit blocks can be emitted once after their
	 * incoming local state has been materialized.  The planner deliberately
	 * rejects handler-local, protected-range, nested, and incomplete regions.
	 */
	private void collectMonitorRegionPlans() {
		monitorRegionPlans.clear();
		sharedMonitorExitBlocks.clear();
		monitorExitLabels.clear();
		skippedMonitorExitBlocks.clear();
		if (!policy.aggressiveCleanup()
				|| !optimizationPlan.featureEnabled(JvmOptimizationFeature.MONITOR_REGIONS)) return;

		Set<IrBlock> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
		for (JvmMonitorRegionCandidate candidate : JvmMonitorRegionPlanner.discover(method)) {
			if (candidate.exits().size() < 2) continue;
			JvmOptimizationDecision decision = optimizationPlan.monitorRegion(candidate);
			if (!decision.accepted()) {
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
						ConversionDiagnostic.Severity.INFO, false,
						"Rejected monitor-region shaping: " + decision.reason());
				continue;
			}
			IrBlock canonical = candidate.normalExitBlocks().isEmpty()
					? candidate.exceptionalExitBlocks().getFirst() : candidate.normalExitBlocks().getFirst();
			List<IrBlock> duplicates = candidate.normalExitBlocks().stream()
					.filter(block -> block != canonical).toList();
			if (duplicates.isEmpty() || claimed.contains(canonical)
					|| duplicates.stream().anyMatch(claimed::contains)) {
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, candidate.sourceOffset(),
						ConversionDiagnostic.Severity.INFO, false,
						"Rejected monitor-region shaping: cleanup exits overlap another planned region");
				continue;
			}
			Map<IrBlock, Map<IrValue, IrValue>> mappings = new IdentityHashMap<>();
			for (IrBlock duplicate : duplicates)
				mappings.put(duplicate, Map.of(candidate.lock().canonical(), duplicateLock(duplicate, candidate.lock())));
			JvmMonitorRegionPlan plan = new JvmMonitorRegionPlan(candidate, canonical, duplicates,
					mappings, decision);
			monitorRegionPlans.add(plan);
			monitorExitLabels.put(plan, new Label());
			claimed.add(canonical);
			claimed.addAll(duplicates);
			for (IrBlock block : List.of(canonical))
				sharedMonitorExitBlocks.put(block, plan);
			for (IrBlock block : duplicates)
				sharedMonitorExitBlocks.put(block, plan);
			skippedMonitorExitBlocks.add(canonical);
			skippedMonitorExitBlocks.addAll(duplicates);
			report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
					"Applied aggressive monitor-region shaping: " + decision.reason());
		}
	}

	private @NotNull IrValue duplicateLock(@NotNull IrBlock block, @NotNull IrValue fallback) {
		for (IrStmt statement : block.statements()) {
			if (statement instanceof IrEffect effect && effect.kind() == me.darknet.dex.convert.ir.statement.IrEffectKind.MONITOR
					&& !effect.inputs().isEmpty()) return effect.inputs().getFirst().canonical();
		}
		return fallback.canonical();
	}

	private @Nullable JvmCleanupTailCandidate cleanupTailCandidate(@NotNull IrBlock block) {
		IrTerminator terminator = block.terminator();
		if (terminator == null || (terminator.kind() != IrTerminatorKind.RETURN
				&& terminator.kind() != IrTerminatorKind.THROW)) return null;
		if (block.statements().isEmpty() || !block.phis().isEmpty() || block.exceptionValue() != null
				|| !block.exceptionalSuccessors().isEmpty() || isExceptionBoundaryGlue(block)) return null;

		List<IrBlock> incoming = block.predecessors().stream()
				.filter(predecessor -> predecessor.successors().contains(block))
			.sorted(Comparator.comparingInt(IrBlock::index)).toList();
		if (incoming.isEmpty()) return null;
		List<IrStmt> statements = new ArrayList<>(block.statements());
		List<IrValue> defined = statements.stream().filter(IrOp.class::isInstance)
				.map(IrOp.class::cast).map(IrValue::canonical).toList();
		Set<IrValue> definedSet = Collections.newSetFromMap(new IdentityHashMap<>());
		definedSet.addAll(defined);
		List<IrValue> required = new ArrayList<>();
		for (IrStmt statement : statements)
			addRequiredValues(statement, definedSet, required);
		addRequiredValues(terminator, definedSet, required);
		List<String> rangeProfile = cleanupTailRangeProfile(block);
		List<String> effects = statements.stream().map(this::cleanupStatementKey).toList();
		List<String> roles = statements.stream().flatMap(statement -> cleanupInputRoles(statement).stream()).toList();
		roles = new ArrayList<>(roles);
		roles.addAll(required.stream().map(this::cleanupValueKey).toList());
		JvmCleanupTailSignature signature = new JvmCleanupTailSignature(effects,
				cleanupTerminatorKey(terminator), rangeProfile, roles);
		return new JvmCleanupTailCandidate(block, block, List.of(block), statements, incoming,
				List.copyOf(block.exceptionEdges()), required, block.startOffset(), rangeProfile, signature);
	}

	private void addRequiredValues(@NotNull IrStmt statement, @NotNull Set<IrValue> defined,
	                               @NotNull List<IrValue> required) {
		List<IrValue> inputs = switch (statement) {
			case IrOp op -> op.inputs();
			case IrEffect effect -> effect.inputs();
			case IrTerminator terminator -> terminator.inputs();
		};
		for (IrValue input : inputs) {
			IrValue canonical = input.canonical();
			if (defined.contains(canonical) || required.stream().anyMatch(value -> value.canonical() == canonical)) continue;
			required.add(canonical);
		}
	}

	private @NotNull List<String> cleanupInputRoles(@NotNull IrStmt statement) {
		List<IrValue> inputs = switch (statement) {
			case IrOp op -> op.inputs();
			case IrEffect effect -> effect.inputs();
			case IrTerminator terminator -> terminator.inputs();
		};
		var semantics = switch (statement) {
			case IrOp op -> op.semantics();
			case IrEffect effect -> effect.semantics();
			case IrTerminator terminator -> terminator.semantics();
		};
		List<String> result = new ArrayList<>();
		for (int index = 0; index < inputs.size(); index++) {
			String role = index < semantics.inputs().size() ? semantics.inputs().get(index).role() : "input";
			result.add(role + "=" + cleanupValueKey(inputs.get(index)));
		}
		return result;
	}

	private @NotNull String cleanupStatementKey(@NotNull IrStmt statement) {
		var semantics = switch (statement) {
			case IrOp op -> op.semantics();
			case IrEffect effect -> effect.semantics();
			case IrTerminator terminator -> terminator.semantics();
		};
		Object payload = switch (statement) {
			case IrOp op -> op.payload();
			case IrEffect effect -> effect.payload();
			case IrTerminator terminator -> terminator.payload();
		};
		String result = statement instanceof IrOp op ? cleanupValueKey(op) : "void";
		return statement.getClass().getSimpleName() + ":" + semantics.loweringId() + ":"
				+ semantics.effect() + ":" + semantics.throwMask() + ":" + cleanupPayloadKey(payload)
				+ ":result=" + result + ":inputs=" + String.join(",", cleanupInputRoles(statement));
	}

	private @NotNull String cleanupTerminatorKey(@NotNull IrTerminator terminator) {
		return cleanupStatementKey(terminator);
	}

	private @NotNull String cleanupValueKey(@NotNull IrValue value) {
		IrType type = value.canonical().irType();
		// Exact reference identity is intentionally absent.  It is an edge
		// argument to the shared tail, while the signature only describes the JVM
		// category and nullability required by the operation.
		return type.kind() + ":" + type.nullability();
	}

	private @NotNull String cleanupPayloadKey(@Nullable Object payload) {
		if (payload == null) return "none";
		if (payload instanceof InvokeInstruction invoke)
			return "invoke:" + invoke.opcode() + ":" + invoke.owner().descriptor() + ":"
					+ invoke.name() + ":" + invoke.type().descriptor();
		if (payload instanceof InstanceFieldInstruction field)
			return "ifield:" + field.opcode() + ":" + field.owner().descriptor() + ":"
					+ field.name() + ":" + field.type().descriptor();
		if (payload instanceof StaticFieldInstruction field)
			return "sfield:" + field.opcode() + ":" + field.owner().descriptor() + ":"
					+ field.name() + ":" + field.type().descriptor();
		if (payload instanceof ArrayInstruction array) return "array:" + array.opcode();
		if (payload instanceof ReturnInstruction returnInstruction) return "return:" + returnInstruction.type();
		if (payload instanceof BranchInstruction branch) return "branch:" + branch.opcode();
		if (payload instanceof BranchZeroInstruction branch) return "branch-zero:" + branch.opcode();
		return payload.getClass().getName();
	}

	private @NotNull List<String> cleanupTailRangeProfile(@NotNull IrBlock block) {
		List<String> profile = new ArrayList<>();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			if (!region.protectedBlocks().contains(block)) continue;
			for (IrExceptionHandler handler : region.handlers()) {
				String caught = handler.handler() == null || handler.handler().isCatchAll() ? "*"
						: handler.handler().exceptionType().descriptor();
				profile.add(region.startOffset() + ":" + region.endOffset() + ":" + caught);
			}
		}
		return profile;
	}

	private boolean cleanupTailProof(@NotNull JvmCleanupTailCandidate canonical,
	                                 @NotNull List<JvmCleanupTailCandidate> duplicates) {
		if (!canonical.exceptionalEdges().isEmpty() || !canonical.exceptionRangeProfile().isEmpty()) return false;
		if (canonical.tailBlocks().size() != 1 || !canonical.terminal()) return false;
		if (!canonical.requiredValues().stream().allMatch(this::cleanupMaterialized)) return false;
		for (JvmCleanupTailCandidate duplicate : duplicates) {
			if (!duplicate.exceptionalEdges().isEmpty() || !duplicate.exceptionRangeProfile().isEmpty()
					|| duplicate.tailBlocks().size() != 1 || !duplicate.terminal()
					|| duplicate.requiredValues().size() != canonical.requiredValues().size()
					|| !duplicate.requiredValues().stream().allMatch(this::cleanupMaterialized)) return false;
			for (int index = 0; index < canonical.requiredValues().size(); index++) {
				IrValue left = canonical.requiredValues().get(index);
				IrValue right = duplicate.requiredValues().get(index);
				if (!sameJvmCategory(left, right)) return false;
				if (left instanceof IrConstant && !sameConstant(left, right)) return false;
			}
		}
		return true;
	}

	private boolean sameJvmCategory(@NotNull IrValue left, @NotNull IrValue right) {
		IrTypeKind leftKind = left.canonical().irType().kind();
		IrTypeKind rightKind = right.canonical().irType().kind();
		return leftKind == rightKind && (leftKind == IrTypeKind.REFERENCE
				|| leftKind == IrTypeKind.INT || leftKind == IrTypeKind.FLOAT
				|| leftKind == IrTypeKind.LONG || leftKind == IrTypeKind.DOUBLE);
	}

	private boolean cleanupMaterialized(@NotNull IrValue value) {
		IrValue canonical = value.canonical();
		return !(canonical instanceof IrUnknown) && !canonical.isImprecise()
				&& !canonical.stackOnly() && (canonical.constantValue() != null || canonical.hasLocal());
	}

	private @NotNull Map<IrValue, IrValue> cleanupTailValueMapping(@NotNull JvmCleanupTailCandidate canonical,
	                                                               @NotNull JvmCleanupTailCandidate duplicate) {
		Map<IrValue, IrValue> mapping = new LinkedHashMap<>();
		for (int index = 0; index < canonical.requiredValues().size(); index++) {
			IrValue destination = canonical.requiredValues().get(index).canonical();
			IrValue source = duplicate.requiredValues().get(index).canonical();
			if (destination instanceof IrConstant && sameConstant(destination, source)) continue;
			mapping.put(destination, source);
		}
		return mapping;
	}

	private void collectDeferredNullThrowBlocks() {
		/*
		 * The DEX form of try (resource) often looks like this in the IR:
		 *
		 *     if (resource == null) goto nullThrow;
		 *     ... body ...
		 *     nullThrow: throw new IllegalStateException(...);
		 *
		 * The nullThrow block may be represented as a tiny nested protected region
		 * which shares the resource-cleanup handler with the real body. Emitting
		 * that region at its original offset makes the JVM exception table split
		 * the try before the null check, so decompilers lose the original try shape.
		 *
		 * For the narrowly recognized pattern, emit the null path next to the body
		 * and give the enclosing region a label immediately before the check. The
		 * resulting bytecode lets decompilers see one protected try containing both the
		 * null check and the resource-consuming code. The cleanup handler remains
		 * available for close()/addSuppressed() reconstruction.
		 */
		deferredNullThrowBlocks.clear();
		relocatedNullResourceRegions.clear();
		deferredNullThrowInsertions.clear();
		if (!policy.aggressiveCleanup()) {
			for (IrExceptionRegion region : method.exceptionRegions()) {
				JvmOptimizationDecision decision = optimizationPlan.resourceRegion(region);
				if (policy.optimized() && !decision.accepted()) continue;
				if (decision.relaxed())
					report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
							"Applied aggressive " + decision.feature() + ": " + decision.reason());
				boolean redundant = region.handlers().stream()
						.anyMatch(handler -> isLegacyRedundantNullResourceRegion(region, handler));
				if (!redundant) continue;
				deferNullThrowBlocks(region, region.protectedBlocks());
			}
			return;
		}
		if (!optimizationPlan.featureEnabled(JvmOptimizationFeature.CLEANUP_REGIONS)) return;
		for (JvmCleanupRegionPlan plan : cleanupPlans.values()) {
			if (!plan.hasNullResourcePath()) continue;
			List<IrBlock> nullThrowSequence = plan.nullResourceSequence(blockByOffset);
			if (!nullThrowSequence.isEmpty())
				deferNullThrowBlocks(plan.region(), nullThrowSequence);
		}
	}

	private boolean hasHandlerStubs(@NotNull IrBlock target) {
		return handlerStubLabels.keySet().stream().anyMatch(key -> key.target() == target);
	}

	private @Nullable IrBlock handlerTailTarget(@NotNull IrBlock root) {
		IrTerminator terminator = root.terminator();
		if (terminator == null || terminator.kind() != IrTerminatorKind.GOTO) return null;
		IrBlock target = gotoTarget(root, terminator.payload());
		if (target == null || target.predecessors().size() != 1 || !target.predecessors().contains(root)
				|| !target.exceptionalSuccessors().isEmpty()
				|| target.terminator() == null || target.terminator().kind() != IrTerminatorKind.THROW)
			return null;
		return target;
	}

	private void emitInitializedPhiValues() {
		initializedPhiValues.clear();
		initializedPhiValues.putAll(analysis.initializedPhiValues(registerLocalBase));
		for (Map.Entry<IrPhi, IrValue> entry : initializedPhiValues.entrySet()) {
			load(entry.getValue(), entry.getKey().type());
			store(entry.getKey());
		}
	}

	private void collectReferencedBlockTargets() {
		for (IrBlock block : method.blocks()) {
			IrBlock fallthrough = layout.nextBlock(block);
			for (IrBlock successor : block.successors())
				if (successor != fallthrough) referencedBlockTargets.add(successor);
		}
	}

	private boolean sameConstant(@NotNull IrValue first, @NotNull IrValue second) {
		return analysis.sameConstant(first, second);
	}

	private void collectHandlerStubs() {
		handlerStubLabels.clear();
		simpleHandlerStubLabels.clear();
		sharedHandlerStubSources.clear();
		stubbedHandlers.clear();
		directHandlerEntries.clear();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			for (IrExceptionHandler handler : region.handlers()) {
				// These regions are deliberately omitted from the JVM exception
				// table below: the null-resource path is relocated into its
				// enclosing protected range, and synthetic rethrow regions are
				// represented by the enclosing handler.  Do not leave orphaned
				// entry labels behind; they create dead goto scaffolding that CFR
				// cannot associate with a source-level try statement.
				if (isSyntheticRethrowRegion(region, handler)
						|| isRedundantNullResourceRegion(region, handler)) continue;
				List<IrBlock> sources = coveredSourceBlocks(region, handler);
				if (canEnterHandlerDirectly(sources, handler.handlerBlock())) {
					if (directHandlerEntries.add(handler.handlerBlock()))
						report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, handler.handlerBlock().startOffset(),
								"Applied aggressive direct handler entry for invariant state");
					continue;
				}
				// Every JVM exception entry has a one-item operand stack, while a
				// handler can also be reached by normal cleanup/control-flow edges.
				// Use an edge-specific bridge for every protected source so the
				// handler body always starts with a materialized local state.
				boolean canShare = !sources.isEmpty() && canShareHandlerStub(sources, handler.handlerBlock());
				if (canShare) {
					simpleHandlerStubLabels.computeIfAbsent(handler.handlerBlock(), ignored -> new Label());
					stubbedHandlers.add(handler.handlerBlock());
					continue;
				}
				if (!sources.isEmpty()) {
					for (IrBlock source : sources)
						handlerStubLabels.computeIfAbsent(new JvmHandlerStubKey(source, handler.handlerBlock()), ignored -> new Label());
					stubbedHandlers.add(handler.handlerBlock());
					continue;
				}
				boolean needsStub = sources.stream().anyMatch(source -> hasPhiCopies(source, handler.handlerBlock()));
				boolean needsSimpleStub = !needsStub
						&& handler.handlerBlock().phis().isEmpty()
						&& startsImmediatelyAfter(region, handler.handlerBlock())
						&& !handler.handlerBlock().predecessors().isEmpty()
						&& handler.handlerBlock().predecessors().stream()
						.allMatch(predecessor -> predecessor.exceptionalSuccessors().contains(handler.handlerBlock()));
				if (needsSimpleStub) {
					simpleHandlerStubLabels.computeIfAbsent(handler.handlerBlock(), ignored -> new Label());
					stubbedHandlers.add(handler.handlerBlock());
					continue;
				}
				if (!needsStub) continue;
				for (IrBlock source : sources) {
					JvmHandlerStubKey key = new JvmHandlerStubKey(source, handler.handlerBlock());
					handlerStubLabels.computeIfAbsent(key, ignored -> new Label());
				}
				stubbedHandlers.add(handler.handlerBlock());
			}
		}
	}

	private boolean canShareHandlerStub(@NotNull List<IrBlock> sources, @NotNull IrBlock target) {
		if (target.exceptionValue() == null) return false;
		if (target.phis().isEmpty()) return sources.stream().noneMatch(source -> hasPhiCopies(source, target));
		if (!policy.aggressiveCleanup()
				|| !optimizationPlan.featureEnabled(JvmOptimizationFeature.CLEANUP_REGIONS)) return false;
		IrBlock representative = commonHandlerPhiSource(sources, target);
		if (representative == null) return false;
		sharedHandlerStubSources.put(target, representative);
		return true;
	}

	/**
	 * Proves that one handler stub can perform all of the target's phi moves.
	 * A shared stub has no source edge identity, so every live phi must receive
	 * the same canonical value from every exceptional predecessor.  This keeps
	 * the optimization independent of DEX register numbers and avoids merging
	 * handler states that only look similar after local allocation.
	 */
	private @Nullable IrBlock commonHandlerPhiSource(@NotNull List<IrBlock> sources,
	                                                  @NotNull IrBlock target) {
		IrBlock representative = sources.getFirst();
		for (IrPhi phi : target.phis()) {
			if (!isLive(phi) || !phi.hasLocal()) continue;
			IrValue expected = phi.operands().get(representative);
			if (expected == null || expected.isUnknown() || expected.isImprecise()) return null;
			IrValue canonical = expected.canonical();
			for (IrBlock source : sources) {
				IrValue input = phi.operands().get(source);
				if (input == null || input.isUnknown() || input.isImprecise()
						|| input.canonical() != canonical
						|| input.type() == null || phi.type() == null
						|| !input.type().descriptor().equals(phi.type().descriptor())) return null;
			}
		}
		return representative;
	}

	private boolean canEnterHandlerDirectly(@NotNull List<IrBlock> sources, @NotNull IrBlock target) {
		if (!policy.aggressiveCleanup()
				|| !optimizationPlan.featureEnabled(JvmOptimizationFeature.CLEANUP_REGIONS)
				|| sources.isEmpty() || target.exceptionValue() == null || target.predecessors().isEmpty()) return false;
		if (target.predecessors().stream().anyMatch(predecessor ->
				!predecessor.exceptionalSuccessors().contains(target))) return false;
		if (target.phis().stream().anyMatch(phi -> phi.canonical() == phi && isLive(phi))) return false;
		return sources.stream().noneMatch(source -> hasPhiCopies(source, target));
	}

	/**
	 * The composite resource plan has already unified the outer failure and
	 * finally entries.  Those selected entries are pure exceptional exits with
	 * materialized state, so their exception value can be stored at the real
	 * handler body even when the original DEX graph contains many split source
	 * ranges.  Resource-close handlers do not use this relaxation: their
	 * source-specific suppression state still requires the ordinary bridge.
	 */
	private boolean startsImmediatelyAfter(@NotNull IrExceptionRegion region, @NotNull IrBlock block) {
		if (block.startOffset() < region.endOffset()) return false;
		return method.blocks().stream().noneMatch(candidate -> candidate.startOffset() > region.endOffset()
				&& candidate.startOffset() < block.startOffset());
	}

	private void analyzeUses() {
		useGraph = analysis.useGraph();
		constructorByReceiver.clear();
		inlineConstructedReceivers.clear();
		directReturnOperands.clear();
		directReturnOperands.addAll(analysis.directReturnOperands());
		collectFullyInlinedReturnBlocks();
		for (IrBlock block : method.blocks()) {
			List<IrStmt> statements = block.statements();
			for (IrStmt statement : statements) {
				if (!(statement instanceof IrOp op) || op.canonical() != op) continue;
				IrOp receiver = constructedReceiver(op);
				if (receiver != null) constructorByReceiver.put(receiver, op);
			}
		}
		if (policy.aggressiveCleanup()
				&& !optimizationPlan.featureEnabled(JvmOptimizationFeature.RECEIVER_CHAINS)) return;
		for (IrBlock block : method.blocks()) {
			List<IrStmt> statements = block.statements();
			for (IrStmt statement : statements) {
				if (!(statement instanceof IrOp op) || op.canonical() != op) continue;
				IrOp receiver = constructedReceiver(op);
				if (receiver == null) continue;
				IrStmt consumer = constructedReceiverConsumer(receiver, op);
				if ((consumer != null && consumesConstructedReceiver(consumer, receiver)
						&& optimizationGuards.allowConstructorChain(op, consumer)
						&& canInlineConstructedReceiver(op, consumer))
						|| optimizationGuards.allowConstructedReceiver(receiver, op)) {
					inlineConstructedReceivers.add(receiver);
				}
			}
		}
	}

	private void collectSingleUsePlans() {
		if (!policy.aggressiveCleanup()
				|| !optimizationPlan.featureEnabled(JvmOptimizationFeature.SINGLE_USE_INLINE)) return;
		Set<IrOp> claimedOperations = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<IrStmt> claimedConsumers = Collections.newSetFromMap(new IdentityHashMap<>());
		for (JvmSingleUseCandidate candidate : JvmSingleUsePlanner.discover(method, useGraph, optimizationGuards)) {
			if (candidate.mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN) continue;
			JvmOptimizationDecision decision = optimizationPlan.singleUse(candidate);
			if (!decision.accepted() || candidate.operations().stream().anyMatch(claimedOperations::contains)
					|| candidate.consumer() != null && claimedConsumers.contains(candidate.consumer())) {
				if (!decision.accepted())
					report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
							ConversionDiagnostic.Severity.INFO, false,
							"Rejected single-use elimination: " + decision.reason());
				else
					report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, candidate.sourceOffset(),
							ConversionDiagnostic.Severity.INFO, false,
							"Rejected overlapping single-use elimination candidate");
				continue;
			}
			JvmSingleUsePlan plan = new JvmSingleUsePlan(candidate, decision);
			singleUsePlans.add(plan);
			for (IrOp operation : candidate.operations()) {
				singleUseByOperation.put(operation, plan);
				claimedOperations.add(operation);
			}
			if (candidate.consumer() != null) {
				singleUseByConsumer.computeIfAbsent(candidate.consumer(), ignored -> new HashMap<>())
						.put(candidate.consumerInputIndex(), plan);
				claimedConsumers.add(candidate.consumer());
			}
			String feature = candidate.mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN
					? "receiver-chain cleanup" : "single-use elimination (" + candidate.mode().name().toLowerCase() + ")";
			if (candidate.mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN)
				feature += " (" + candidate.operations().size() + " operations)";
			report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
					"Applied aggressive " + feature);
		}
	}

	private void collectReceiverChainPlans() {
		if (!policy.aggressiveCleanup()
				|| !optimizationPlan.featureEnabled(JvmOptimizationFeature.RECEIVER_CHAINS)) return;
		Set<IrOp> claimedOperations = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<IrStmt> claimedConsumers = Collections.newSetFromMap(new IdentityHashMap<>());
		for (JvmSingleUsePlan existing : singleUsePlans) {
			claimedOperations.addAll(existing.candidate().operations());
			if (existing.candidate().consumer() != null) claimedConsumers.add(existing.candidate().consumer());
		}
		for (JvmSingleUseCandidate candidate : JvmSingleUsePlanner.discover(method, useGraph, optimizationGuards)) {
			if (candidate.mode() != JvmSingleUseCandidate.Mode.RECEIVER_CHAIN) continue;
			JvmOptimizationDecision decision = optimizationPlan.singleUse(candidate);
			if (!decision.accepted()) {
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
						ConversionDiagnostic.Severity.INFO, false,
						"Rejected receiver-chain cleanup: " + decision.reason());
				continue;
			}
			if (candidate.operations().stream().anyMatch(claimedOperations::contains)
					|| candidate.consumer() != null && claimedConsumers.contains(candidate.consumer())) {
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, candidate.sourceOffset(),
						ConversionDiagnostic.Severity.INFO, false,
						"Rejected overlapping receiver-chain cleanup candidate");
				continue;
			}
			JvmSingleUsePlan plan = new JvmSingleUsePlan(candidate, decision);
			singleUsePlans.add(plan);
			candidate.operations().forEach(operation -> {
				singleUseByOperation.put(operation, plan);
				claimedOperations.add(operation);
			});
			if (candidate.consumer() != null) {
				singleUseByConsumer.computeIfAbsent(candidate.consumer(), ignored -> new HashMap<>())
						.put(candidate.consumerInputIndex(), plan);
				claimedConsumers.add(candidate.consumer());
			}
			report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
					"Applied aggressive receiver-chain cleanup (" + candidate.operations().size() + " operations)");
		}
	}

	private void initializeLabels() {
		protectedBoundaryLabels.clear();
		layout.initializeLabels();
	}

	/**
	 * Transparent blocks can become apparently empty after their operations are
	 * marked emitted. Coalesce transparent glue against the first planned
	 * nontransparent block before emission so later alias collection cannot
	 * mistake that real block for transparent glue and create a backward bridge.
	 */
	private void coalesceTransparentLabelsForDeferredBlocks() {
		if (!policy.aggressiveCleanup()) return;
		Set<IrBlock> owned = Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrBlock block : layout.emissionOrder()) {
			if (!exceptionLayoutPlan.skipped(block) && !layoutTransparentBlocks.contains(block))
				owned.add(block);
		}
		owned.addAll(deferredNullThrowBlocks);
		for (IrBlock block : method.blocks()) {
			if (!isAliasGlue(block)) continue;
			Label targetLabel = aliasOwnerLabel(block, labels.get(block), owned,
					Collections.newSetFromMap(new IdentityHashMap<>()));
			if (targetLabel != null) labels.put(block, targetLabel);
		}
	}

	private boolean isAliasGlue(@NotNull IrBlock block) {
		if (layoutTransparentBlocks.contains(block)
				|| policy.aggressiveCleanup() && isTransparentBlock(block)) return true;
		if (!exceptionLayoutPlan.skipped(block) || !block.phis().isEmpty()
				|| block.exceptionValue() != null || !block.exceptionalSuccessors().isEmpty()
				|| !block.exceptionEdges().isEmpty() || !block.statements().isEmpty()
				|| block.successors().size() != 1) return false;
		IrTerminator terminator = block.terminator();
		return terminator != null && terminator.kind() == IrTerminatorKind.GOTO
				&& gotoTarget(block, terminator.payload()) == block.successors().getFirst()
				&& !sharedCleanupTails.containsKey(block) && !sharedMonitorExitBlocks.containsKey(block)
				&& handlerTails.values().stream().noneMatch(tail -> tail.root() == block || tail.target() == block);
	}

	private @Nullable Label aliasOwnerLabel(@NotNull IrBlock block,
	                                        @Nullable Label originLabel,
	                                        @NotNull Set<IrBlock> owned,
	                                        @NotNull Set<IrBlock> visited) {
		if (!visited.add(block)) return null;
		Label plannedTail = plannedTailLabel(block);
		if (plannedTail != null && plannedTail != originLabel) return plannedTail;
		if (owned.contains(block) && labels.get(block) != originLabel)
			return labels.get(block);
		if ((!isAliasGlue(block) && !isPlainGotoGlue(block)) || block.successors().size() != 1)
			return null;
		return aliasOwnerLabel(block.successors().getFirst(), originLabel, owned, visited);
	}

	private boolean isPlainGotoGlue(@NotNull IrBlock block) {
		if (!block.phis().isEmpty() || block.exceptionValue() != null
				|| !block.exceptionalSuccessors().isEmpty() || !block.exceptionEdges().isEmpty()
				|| !block.statements().isEmpty() || block.successors().size() != 1
				|| sharedCleanupTails.containsKey(block) || sharedMonitorExitBlocks.containsKey(block)
				|| handlerTails.values().stream().anyMatch(tail -> tail.root() == block || tail.target() == block)
				|| isExceptionBoundaryGlue(block)) return false;
		IrTerminator terminator = block.terminator();
		return terminator != null && terminator.kind() == IrTerminatorKind.GOTO
				&& gotoTarget(block, terminator.payload()) == block.successors().getFirst();
	}

	private void captureLayoutTransparency() {
		layoutTransparentBlocks.clear();
		if (!policy.aggressiveCleanup()) return;
		for (IrBlock block : layout.emissionOrder())
			if (isTransparentBlock(block)) layoutTransparentBlocks.add(block);
	}

	/**
	 * Discovers only the loop facts enabled by the selected policy.  The first
	 * slice intentionally keeps the source block order: accepted plans are
	 * therefore safe to use for branch polarity and induction proofs without
	 * changing protected-range label order.  The layout object still exposes a
	 * lowering-only order for a later, fully proven layout transformation.
	 */
	private void collectLoopShapePlans() {
		loopShapePlans.clear();
		loopShapeByBlock.clear();
		if (!policy.loopRestructuring()
				|| !optimizationPlan.featureEnabled(JvmOptimizationFeature.LOOP_RESTRUCTURE)) return;
		Set<IrBlock> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
		for (JvmLoopShapeCandidate candidate : JvmLoopShapePlanner.discover(method, optimizationGuards)) {
			JvmOptimizationDecision decision = optimizationPlan.loopShape(candidate);
			if (!decision.accepted()) {
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
						ConversionDiagnostic.Severity.INFO, false,
						"Rejected loop restructuring: " + decision.reason());
				continue;
			}
			if (candidate.loopBlocks().stream().anyMatch(claimed::contains)) {
				report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, candidate.sourceOffset(),
						ConversionDiagnostic.Severity.INFO, false,
						"Rejected overlapping loop restructuring candidate");
				continue;
			}
			// No block is moved in this slice.  Keeping the maps explicit makes
			// branch decisions share the same proof model as future layout work.
			Map<IrBlock, IrBlock> preferredFallthrough = new IdentityHashMap<>();
			Map<IrBlock, Boolean> branchInverted = new IdentityHashMap<>();
			for (IrBlock predicate : candidate.predicateBlocks()) {
				IrBlock next = layout.nextBlock(predicate);
				if (next != null && predicate.successors().contains(next)) {
					preferredFallthrough.put(predicate, next);
					if (predicate.terminator() != null && predicate.terminator().payload() instanceof BranchInstruction branch)
						branchInverted.put(predicate, blockByOffset.get(branch.label().position()) == next);
					else if (predicate.terminator() != null && predicate.terminator().payload() instanceof BranchZeroInstruction branch)
						branchInverted.put(predicate, blockByOffset.get(branch.label().position()) == next);
				}
			}
			JvmLoopShapePlan plan = new JvmLoopShapePlan(candidate, method.blocks(), preferredFallthrough, branchInverted,
					Map.of(), decision);
			loopShapePlans.add(plan);
			candidate.loopBlocks().forEach(block -> {
				claimed.add(block);
				loopShapeByBlock.put(block, plan);
			});
			report(ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION, decision.sourceOffset(),
					"Applied aggressive loop shape " + candidate.kind().name().toLowerCase());
		}
	}

	private boolean acceptedCountedLoopFor(@NotNull IrOp operation) {
		if (policy != JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED) return true;
		if (!optimizationPlan.featureEnabled(JvmOptimizationFeature.LOOP_RESTRUCTURE)) return false;
		IrBlock block = blockContaining(operation);
		return loopShapePlans.stream().anyMatch(plan -> plan.accepted()
				&& plan.candidate().kind() == JvmLoopShapeKind.COUNTED
				&& plan.candidate().backedge() == block);
	}

	private void collectFullyInlinedReturnBlocks() {
		fullyInlinedReturnBlocks.clear();
		// Aggressive lowering has additional cleanup/layout rewrites.  Keep
		// return blocks materialized there until the authoritative exception
		// layout can prove that every incoming edge is redirected; otherwise a
		// transparent predecessor may retain a jump to an un-emitted return
		// label.  Guarded output keeps the historical direct-return behavior.
		if (!policy.optimized() || policy.aggressiveCleanup()) return;
		for (IrBlock block : method.blocks()) {
			if (!block.statements().isEmpty() || block.exceptionValue() != null
					|| !block.exceptionalSuccessors().isEmpty()) continue;
			List<IrBlock> predecessors = block.predecessors().stream()
					.filter(predecessor -> predecessor.successors().contains(block))
					.toList();
			if (!predecessors.isEmpty() && predecessors.stream()
					.anyMatch(predecessor -> !canEmitDirectPhiReturn(predecessor, block))) continue;
			if (!predecessors.isEmpty()) fullyInlinedReturnBlocks.add(block);
		}
	}

	private int emitSpecialChain(@NotNull List<IrStmt> statements, int index) {
		return specialChainEmitter.emit(statements, index, new IrSpecialChainEmitter.Host() {
			@Override
			public boolean tryEmitConstructAndPutChain(@NotNull List<IrStmt> values, int valueIndex) {
				return IrLoweringEngine.this.tryEmitConstructAndPutChain(values, valueIndex);
			}

			@Override
			public boolean tryEmitConstructAndPutChainAcrossBlocks(@NotNull List<IrStmt> values, int valueIndex) {
				return IrLoweringEngine.this.tryEmitConstructAndPutChainAcrossBlocks(values, valueIndex);
			}

			@Override
			public int tryEmitArrayStaticPutChain(@NotNull List<IrStmt> values, int valueIndex) {
				return IrLoweringEngine.this.tryEmitArrayStaticPutChain(values, valueIndex);
			}
		});
	}

	private boolean shouldSkipSeparateEmission(@NotNull List<IrStmt> statements, int index) {
		return shouldSkipSeparateEmission(statements, index, null);
	}

	private boolean shouldSkipSeparateEmission(@NotNull List<IrStmt> statements, int index,
	                                           @Nullable IrTerminator blockTerminator) {
		if (!policy.optimized()) return false;
		return expressionPlanner.shouldSkipSeparateEmission(statements, index, blockTerminator,
				this::computeShouldSkipSeparateEmission);
	}

	private boolean computeShouldSkipSeparateEmission(@NotNull List<IrStmt> statements, int index,
	                                                   @Nullable IrTerminator blockTerminator) {
		IrStmt statement = statements.get(index);
		if (!(statement instanceof IrOp op) || op.canonical() != op)
			return false;
		if (policy.aggressiveCleanup() && emittedOps.contains(op)) return true;
		if (policy.aggressiveCleanup()) {
			if (materializationPlan != null && materializationPlan.skipsSeparateEmission(op)) return true;
			// Do not let the legacy expression/constructor peepholes skip a
			// suffix of a receiver chain whose complete proof was rejected.  The
			// authoritative fallback for such chains is ordinary local emission.
			if (isReceiverChainOperation(op)) return false;
		}
		// A conversion feeding a proven in-place long accumulator is emitted by
		// the accumulator proof itself.  Keeping this decision next to the
		// emission proof prevents the producer from being dropped when a later
		// expression-planner rule changes.
		if (isLongIncrementConversion(op)) return true;
		if (isSyntheticLambdaConstruction(op))
			return true;
		boolean constructorOperation = op.payload() instanceof InvokeInstruction instruction
				&& isConstructorInvoke(instruction);
		if (constructorOperation && isSyntheticLambdaConstructor(op))
			return true;
		boolean stringBuilderConstructor = constructorOperation && !op.inputs().isEmpty()
				&& isStringBuilder(op.inputs().getFirst());
		IrStmt constructedConsumer = constructorOperation && !op.inputs().isEmpty()
				&& op.inputs().getFirst().canonical() instanceof IrOp receiver
				? constructedReceiverConsumer(receiver, op) : null;
		boolean thrownConstructor = constructedConsumer instanceof IrTerminator terminator
				&& terminator.kind() == IrTerminatorKind.THROW;
		if (!optimizationGuards.safeOperation(op)
				&& !((stringBuilderConstructor || thrownConstructor) && optimizationGuards.safeStatement(op))) return false;
		if (usesActiveOperandStackCarry(op))
			return false;
		IrStmt next = index + 1 < statements.size() ? statements.get(index + 1) : blockTerminator;
		if (op.payload() instanceof InvokeInstruction constructorInstruction
				&& isConstructorInvoke(constructorInstruction) && !op.inputs().isEmpty()
				&& op.inputs().getFirst().canonical() instanceof IrOp receiver
				&& inlineConstructedReceivers.contains(receiver)
				&& (isStringBuilder(receiver) || thrownConstructor)
				&& (thrownConstructor
					? optimizationGuards.allowConstructorChain(op, constructedConsumer)
					: optimizationGuards.allowConstructedReceiver(receiver, op)))
			return true;
		if (next != null && optimizationGuards.allowReceiverReturningChain(op, next)) {
			// Aggressive receiver chains are governed by the shared single-use
			// plan.  The older local heuristic may recognize only a suffix of a
			// fluent chain; skipping that suffix would drop the intervening calls
			// when the complete-chain proof is rejected.
			if (!policy.aggressiveCleanup()) return true;
			JvmSingleUsePlan receiverPlan = singleUseByOperation.get(op);
			return receiverPlan != null && receiverPlan.accepted()
					&& receiverPlan.candidate().mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN;
		}
		if ((!policy.aggressiveCleanup()
				|| optimizationPlan.featureEnabled(JvmOptimizationFeature.RECEIVER_CHAINS))
				&& shouldInlineConstructedReceiverConstructor(op))
			return true;
		if ((!policy.aggressiveCleanup()
				|| optimizationPlan.featureEnabled(JvmOptimizationFeature.RECEIVER_CHAINS))
				&& shouldDeferConstructedReceiver(op))
			return true;
		if ((!policy.aggressiveCleanup()
				|| optimizationPlan.featureEnabled(JvmOptimizationFeature.RECEIVER_CHAINS))
				&& shouldInlineConstructedReceiver(op, next))
			return true;
		if ((!policy.aggressiveCleanup()
				|| optimizationPlan.featureEnabled(JvmOptimizationFeature.RECEIVER_CHAINS))
				&& isConstructorReceiverPair(op, next))
			return true;
		if (!canDeferEmissionToConsumer(op))
			return false;
		if (!canInlineValue(op))
			return false;
		IrStmt consumer = singleConsumerStatement(op);
		if (consumer == null)
			return false;
		if (!optimizationGuards.allowInline(op, consumer)) return false;
		int consumerIndex = consumer == blockTerminator ? statements.size() : statements.indexOf(consumer);
		if (consumerIndex <= index) {
			return consumerIndex < 0 && optimizationGuards.allowAdjacentInline(op, consumer);
		}
		for (int i = index + 1; i < consumerIndex; i++)
			if (!shouldSkipSeparateEmission(statements, i, blockTerminator))
				return false;
		return true;
	}

	private boolean canDeferEmissionAcrossBlocks(@NotNull IrOp op, @NotNull List<IrStmt> statements, int index,
	                                             @NotNull IrStmt consumer, @Nullable IrTerminator blockTerminator) {
		// Cross-block stack movement remains deliberately disabled until a complete
		// exceptional-CFG proof exists. Values crossing blocks use locals.
		return false;
		/*
		if (!(op.payload() instanceof InvokeInstruction)
				&& !(op.payload() instanceof InstanceFieldInstruction)
				&& !(op.payload() instanceof StaticFieldInstruction)) return false;
		IrBlock sourceBlock = method.blocks().stream()
				.filter(block -> block.statements() == statements)
				.findFirst().orElse(null);
		IrBlock consumerBlock = method.blocks().stream()
				.filter(block -> block.statements().contains(consumer))
				.findFirst().orElse(null);
		if (sourceBlock == null || consumerBlock == null || sourceBlock == consumerBlock)
			return false;
		int sourceIndex = method.blocks().indexOf(sourceBlock);
		int consumerBlockIndex = method.blocks().indexOf(consumerBlock);
		if (consumerBlockIndex <= sourceIndex || sourceBlock.successors().size() != 1
				|| sourceBlock.successors().getFirst() != nextBlock(sourceBlock))
			return false;
		for (int i = index + 1; i < statements.size(); i++)
			if (!shouldSkipSeparateEmission(statements, i, blockTerminator))
				return false;
		for (int i = sourceIndex + 1; i < consumerBlockIndex; i++) {
			IrBlock block = method.blocks().get(i);
			if (!block.phis().isEmpty() || block.exceptionValue() != null
					|| (!block.exceptionalSuccessors().isEmpty() && !canMoveAcrossExceptionalGlue(op))
					|| hasEmittableStatements(block) || block.successors().size() != 1
					|| block.successors().getFirst() != nextBlock(block))
				return false;
		}
		boolean safe = sourceBlock.exceptionalSuccessors().equals(consumerBlock.exceptionalSuccessors());
		if (safe) op.stackOnly(true);
		return safe;
		*/
	}

	private boolean isReceiverChainOperation(@NotNull IrOp op) {
		if (isReceiverReturningInvoke(op)) return true;
		if (op.payload() instanceof NewInstanceInstruction)
			return useGraph != null && useGraph.useSites(op).stream().anyMatch(site ->
					site.consumer() instanceof IrOp consumer && !consumer.inputs().isEmpty()
							&& consumer.inputs().getFirst().canonical() == op
							&& consumer.payload() instanceof InvokeInstruction invoke
							&& isReceiverReturningInvoke(invoke));
		if (!(op.payload() instanceof InvokeInstruction instruction) || !isConstructorInvoke(instruction)) return false;
		if (op.inputs().isEmpty() || !(op.inputs().getFirst().canonical() instanceof IrOp receiver)) return false;
		return isReceiverChainOperation(receiver);
	}

	private boolean acceptedReceiverChainOperation(@NotNull IrOp op) {
		JvmSingleUsePlan plan = singleUseByOperation.get(op);
		return plan != null && plan.accepted()
				&& plan.candidate().mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN;
	}

	private boolean canMoveConstructedReceiverOp(@NotNull IrOp op) {
		if (!(op.payload() instanceof InvokeInstruction) || op.inputs().isEmpty()) return false;
		return op.inputs().getFirst().canonical() instanceof IrOp receiver
				&& receiver.payload() instanceof NewInstanceInstruction
				&& inlineConstructedReceivers.contains(receiver);
	}

	private boolean canMoveAcrossExceptionalGlue(@NotNull IrOp op) {
		if (canMoveConstructedReceiverOp(op)) return true;
		if (!(op.payload() instanceof InstanceFieldInstruction) || op.inputs().isEmpty()
				|| !(op.inputs().getFirst().canonical() instanceof IrParameter parameter)
				|| (method.source().getAccess() & ACC_STATIC) != 0) return false;
		int receiverRegister = method.source().getCode().getRegisters() - method.source().getCode().getIn();
		return parameter.register() == receiverRegister;
	}

	private boolean tryCarryInvokeInput(@NotNull IrBlock block, @NotNull List<IrStmt> statements, int index,
	                                    @Nullable IrTerminator blockTerminator) {
		IrStmt statement = statements.get(index);
		if (!(statement instanceof IrOp op) || op.canonical() != op)
			return false;
		if (policy.aggressiveCleanup() && isReceiverChainOperation(op)
				&& !acceptedReceiverChainOperation(op)) return false;
		if (ConversionSupport.isVoidType(op.type()) || op.payload() instanceof NewInstanceInstruction
				|| useCount(op) != 1)
			return false;
		IrStmt consumer = singleConsumerStatement(op);
		if (!(consumer instanceof IrOp) && !(consumer instanceof IrTerminator))
			return false;
		IrOp consumerOp = consumer instanceof IrOp invokeConsumer ? invokeConsumer : null;
		if (consumerOp != null && !(consumerOp.payload() instanceof InvokeInstruction)
				&& !(consumerOp.payload() instanceof CheckCastInstruction))
			return false;
		if (consumerOp != null && consumerOp.payload() instanceof InvokeInstruction instruction
				&& ("Z".equals(instruction.type().returnType().descriptor())
				|| "V".equals(instruction.type().returnType().descriptor())))
			return false;
		if (consumer instanceof IrTerminator terminator
				&& terminator.kind() != IrTerminatorKind.IF && terminator.kind() != IrTerminatorKind.IF_ZERO)
			return false;
		int consumerInputIndex = -1;
		List<IrValue> consumerInputs = consumer instanceof IrOp
				? consumerOp.inputs() : ((IrTerminator) consumer).inputs();
		for (int inputIndex = 0; inputIndex < consumerInputs.size(); inputIndex++) {
			if (consumerInputs.get(inputIndex).canonical() == op) {
				consumerInputIndex = inputIndex;
				break;
			}
		}
		if (consumerInputIndex < 0)
			return false;
		boolean carriedNonFirstInput = consumerInputIndex > 0
				&& consumerOp != null && consumerOp.payload() instanceof InvokeInstruction
				&& hasCarriedInvokeInputPrefix(consumerOp, consumerInputIndex);
		boolean skipSeparate = shouldSkipSeparateEmission(statements, index, blockTerminator);
		if (skipSeparate && !carriedNonFirstInput)
			return false;
		if (op.stackOnly() && !carriedNonFirstInput)
			return false;
		IrBlock consumerBlock = method.blocks().stream()
				.filter(candidate -> candidate.statements().contains(consumer) || candidate.terminator() == consumer)
				.findFirst().orElse(null);
		if (consumerBlock == null)
			return false;
		if (!optimizationGuards.allowCarry(block, op, consumer)) return false;
		if (consumerBlock != block && method.blocks().indexOf(consumerBlock) < method.blocks().indexOf(block))
			return false;
		if (usesActiveOperandStackCarry(op))
			return false;
		Set<IrBlock> carryRegion = null;
		if (consumerBlock == block) {
			if (consumerOp == null) {
				// A branch consumes the producer directly after the final statement.
				// There is no invoke prefix to preserve in this case.
				if (!(consumer instanceof IrTerminator terminator)
						|| (terminator.kind() != IrTerminatorKind.IF
						&& terminator.kind() != IrTerminatorKind.IF_ZERO)
						|| index != statements.size() - 1)
					return false;
			} else if (statements.indexOf(consumer) <= index || !canNestOperandStackCarry(block, op, consumer)
					|| !hasCarriedInvokeInputPrefix(consumerOp, consumerInputIndex))
				return false;
		} else {
			if (consumerInputIndex != 0 || !operandStackState.isEmpty()
					|| (consumerOp != null && consumerOp.stackOnly())
					|| isTransparentBlock(consumerBlock))
				return false;
			carryRegion = operandStackCarryRegion(block, consumerBlock);
			boolean directConditionCarry = consumer instanceof IrTerminator;
			boolean hasIntermediateExceptionalBlock = carryRegion != null && carryRegion.stream()
					.anyMatch(candidate -> candidate != block && !candidate.exceptionalSuccessors().isEmpty());
			boolean hasExceptionalBlock = carryRegion != null && carryRegion.stream()
					.anyMatch(candidate -> !candidate.exceptionalSuccessors().isEmpty());
			if (consumerInputIndex != 0 || carryRegion == null || carryRegion.stream()
					.anyMatch(candidate -> candidate != block && operandStackState.contains(candidate))
					|| (directConditionCarry ? hasIntermediateExceptionalBlock
					: (!(consumerOp != null && consumerOp.payload() instanceof CheckCastInstruction)
					&& !canCarryAcrossExceptionalSuccessors(op) && hasExceptionalBlock)))
				return false;
		}

		IrStmt previousStatement = currentStatement;
		currentStatement = op;
		emitOp(op, IrOperationEmitter.ResultMode.LEAVE_ON_STACK);
		currentStatement = previousStatement;
		emittedOps.add(op);
		OperandStackState.Carry carry = new OperandStackState.Carry(op, consumer, consumerBlock);
		operandStackState.push(carry);
		expressionPlanner.invalidate();
		if (carryRegion != null) {
			for (IrBlock candidate : carryRegion)
				if (candidate != block)
					operandStackState.assign(candidate, op, consumer, consumerBlock);
		}
		return true;
	}

	private boolean canCarryAcrossExceptionalSuccessors(@NotNull IrOp op) {
		if (op.payload() instanceof InvokeInstruction) {
			// A receiver-returning invoke is still an effectful operation. Keeping its
			// result on the operand stack across handler-split blocks can interleave that
			// stack value with a later receiver chain, producing an invalid JVM stack.
			// Let the consumer inline the invoke instead; it preserves the original
			// evaluation order and gives every block a balanced entry stack.
			return false;
		}
		if (op.payload() instanceof StaticFieldInstruction) return true;
		if (!(op.payload() instanceof InstanceFieldInstruction) || op.inputs().isEmpty()
				|| !(op.inputs().getFirst().canonical() instanceof IrParameter parameter)
				|| (method.source().getAccess() & ACC_STATIC) != 0)
			return false;
		int receiverRegister = method.source().getCode().getRegisters() - method.source().getCode().getIn();
		return parameter.register() == receiverRegister;
	}

	private @Nullable Set<IrBlock> operandStackCarryRegion(@NotNull IrBlock source, @NotNull IrBlock target) {
		Set<IrBlock> reachable = new HashSet<>();
		ArrayDeque<IrBlock> worklist = new ArrayDeque<>();
		reachable.add(source);
		worklist.add(source);
		while (!worklist.isEmpty()) {
			IrBlock block = worklist.removeFirst();
			if (block == target) continue;
			for (IrBlock successor : block.successors()) {
				if (reachable.add(successor)) worklist.addLast(successor);
			}
		}
		if (!reachable.contains(target)) return null;

		Set<IrBlock> reachesTarget = new HashSet<>();
		reachesTarget.add(target);
		worklist.add(target);
		while (!worklist.isEmpty()) {
			IrBlock block = worklist.removeFirst();
			for (IrBlock predecessor : block.predecessors()) {
				if (predecessor.successors().contains(block) && reachesTarget.add(predecessor))
					worklist.addLast(predecessor);
			}
		}

		reachable.retainAll(reachesTarget);
		for (IrBlock block : reachable) {
			if (block != source) {
				for (IrBlock predecessor : block.predecessors()) {
					if (!reachable.contains(predecessor) || predecessor.exceptionalSuccessors().contains(block))
						return null;
				}
			}
			if (block != target && !reachable.containsAll(block.successors())) return null;
		}
		return reachable;
	}

	private void beginOperandStackCarry(@NotNull IrBlock block) {
		// shouldSkipSeparateEmission depends on the active carry set. Do not reuse
		// results computed while inspecting this block before its carry was entered.
		expressionPlanner.invalidate();
		operandStackState.begin(block);
	}

	private void clearOperandStackCarry() {
		operandStackState.clear();
		expressionPlanner.invalidate();
	}

	private boolean hasUnconsumedOperandStackCarry(@NotNull IrBlock block) {
		for (OperandStackState.Carry carry : operandStackState.activeCarries())
			if (carry.consumerBlock() == block) return true;
		return false;
	}

	private boolean canNestOperandStackCarry(@NotNull IrBlock block, @NotNull IrOp producer,
	                                         @NotNull IrStmt consumer) {
		OperandStackState.Carry outerCarry = operandStackState.peekLast();
		if (outerCarry == null || outerCarry.consumerBlock() != block || outerCarry.consumer() == consumer)
			return true;
		if (block.statements().indexOf(consumer) >= block.statements().indexOf(outerCarry.consumer())) return false;
		if (outerCarry.consumer() instanceof IrOp outerConsumer
				&& outerConsumer.payload() instanceof InvokeInstruction) {
			for (int inputIndex = 1; inputIndex < outerConsumer.inputs().size(); inputIndex++) {
				if (dependsOn(outerConsumer.inputs().get(inputIndex), producer, new HashSet<>()))
					return hasCarriedInvokeInputPrefix(outerConsumer, inputIndex);
			}
		}
		return true;
	}

	private boolean dependsOn(@NotNull IrValue value, @NotNull IrOp producer,
	                          @NotNull Set<IrValue> visited) {
		IrValue canonical = value.canonical();
		if (canonical == producer) return true;
		if (!visited.add(canonical) || !(canonical instanceof IrOp op)) return false;
		for (IrValue input : op.inputs())
			if (dependsOn(input, producer, visited)) return true;
		return false;
	}

	private boolean hasCarriedInvokeInputPrefix(@NotNull IrOp consumer, int inputIndex) {
		var carries = operandStackState.activeCarries().descendingIterator();
		for (int index = inputIndex - 1; index >= 0; index--) {
			if (!carries.hasNext() || carries.next().value() != consumer.inputs().get(index).canonical()) return false;
		}
		return true;
	}

	private boolean usesActiveOperandStackCarry(@NotNull IrOp op) {
		for (IrValue input : op.inputs())
			if (activeOperandStackCarry(input) != null) return true;
		return false;
	}

	private @Nullable OperandStackState.Carry activeOperandStackCarry(@NotNull IrValue value) {
		IrValue canonical = value.canonical();
		for (OperandStackState.Carry carry : operandStackState.activeCarries())
			if (carry.value() == canonical) return carry;
		return null;
	}

	private IrStmt singleConsumerStatement(@NotNull IrValue value) {
		return useGraph.singleStatementConsumer(value);
	}

	private int useCount(@NotNull IrValue value) {
		return useGraph.useCount(value);
	}

	private boolean isLive(@NotNull IrValue value) {
		return useGraph.isLive(value);
	}

	private boolean shouldStoreResult(@NotNull IrValue value) {
		return useCount(value) > 0 && value.hasLocal() && !ConversionSupport.isVoidType(value.type());
	}

	private boolean shouldKeepConstructedInstance(@NotNull IrOp newInstanceOp) {
		return useCount(newInstanceOp) > 1;
	}

	private static boolean canInlineValue(@NotNull IrOp op) {
		return !(op.payload() instanceof NewInstanceInstruction);
	}

	private static boolean isConstructorInvoke(@NotNull InvokeInstruction instruction) {
		return instruction.opcode() == Invoke.DIRECT && "<init>".equals(instruction.name());
	}

	private boolean isSyntheticLambdaConstruction(@NotNull IrOp op) {
		if (!(op.payload() instanceof NewInstanceInstruction)) return false;
		IrOp constructor = syntheticLambdaConstructor(op);
		IrOp consumer = syntheticLambdaConsumer(op);
		return constructor != null && consumer != null
				&& lambdaTarget(op) != null
				&& optimizationGuards.allowSyntheticLambda(op, constructor, consumer);
	}

	private boolean isSyntheticLambdaConstructor(@NotNull IrOp op) {
		if (!(op.payload() instanceof InvokeInstruction instruction) || !isConstructorInvoke(instruction)
				|| op.inputs().isEmpty() || !(op.inputs().getFirst().canonical() instanceof IrOp comparator))
			return false;
		IrOp consumer = syntheticLambdaConsumer(comparator);
		return consumer != null && syntheticLambdaConstructor(comparator) == op
				&& lambdaTarget(comparator) != null
				&& optimizationGuards.allowSyntheticLambda(comparator, op, consumer);
	}

	private boolean tryEmitSyntheticLambda(@NotNull IrOp consumer,
	                                      @NotNull InvokeInstruction instruction,
	                                      int inputIndex) {
		if (!policy.guardedExpressions() || consumer.inputs().size() <= inputIndex)
			return false;
		IrValue value = consumer.inputs().get(inputIndex).canonical();
		if (!(value instanceof IrOp comparator) || !(comparator.payload() instanceof NewInstanceInstruction))
			return false;
		JvmLambdaMetadata.Target target = lambdaTarget(comparator);
		if (target == null || !lambdaConsumer(instruction, target.kind(), inputIndex)) return false;
		IrOp constructor = syntheticLambdaConstructor(comparator);
		IrOp sort = syntheticLambdaConsumer(comparator);
		if (constructor == null || sort != consumer
				|| !optimizationGuards.allowSyntheticLambda(comparator, constructor, consumer))
			return false;

		if (target.kind() == JvmLambdaMetadata.Kind.COMPARATOR) {
			Type erasedCompare = Type.getMethodType("(Ljava/lang/Object;Ljava/lang/Object;)I");
			Type byteArrayCompare = Type.getMethodType("([B[B)I");
			mv.visitInvokeDynamicInsn("compare", "()Ljava/util/Comparator;", LAMBDA_METAFACTORY,
					erasedCompare,
					new Handle(H_INVOKESTATIC, target.owner(), target.name(), target.descriptor(), false),
					byteArrayCompare);
		} else if (target.kind() == JvmLambdaMetadata.Kind.FUNCTION) {
			Type erasedApply = Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;");
			String returnDescriptor = target.descriptor().substring(target.descriptor().indexOf(')') + 1);
			Type instantiatedApply = Type.getMethodType("(L" + target.owner() + ";)" + returnDescriptor);
			int handleKind = target.invokeKind() == Invoke.INTERFACE ? H_INVOKEINTERFACE : H_INVOKEVIRTUAL;
			mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", LAMBDA_METAFACTORY,
					erasedApply,
					new Handle(handleKind, target.owner(), target.name(), target.descriptor(),
						target.invokeKind() == Invoke.INTERFACE),
					instantiatedApply);
		} else {
			Type[] captures = Type.getArgumentTypes("(" + target.captureDescriptor() + ")V");
			if (captures.length + 1 != constructor.inputs().size()) return false;
			for (int capture = 1; capture < constructor.inputs().size(); capture++)
				load(constructor.inputs().get(capture), semanticInputType(constructor, capture));
			Type erasedRun = Type.getMethodType("()V");
			Type capturedRun = Type.getMethodType("(" + target.captureDescriptor() + ")Ljava/lang/Runnable;");
			int handleKind = target.invokeKind() == Invoke.INTERFACE ? H_INVOKEINTERFACE : H_INVOKEVIRTUAL;
			mv.visitInvokeDynamicInsn("run", capturedRun.getDescriptor(), LAMBDA_METAFACTORY,
					erasedRun,
					new Handle(handleKind, target.owner(), target.name(), target.descriptor(),
						target.invokeKind() == Invoke.INTERFACE),
					erasedRun);
		}
		return true;
	}

	private @Nullable IrOp syntheticLambdaConstructor(@NotNull IrOp comparator) {
		IrBlock block = blockContaining(comparator);
		if (block == null) return null;
		IrOp constructor = findSyntheticLambdaConstructor(block, comparator, block.statements().indexOf(comparator) + 1);
		if (constructor != null) return constructor;
		if (block.successors().size() != 1) return null;
		IrBlock next = block.successors().getFirst();
		return findSyntheticLambdaConstructor(next, comparator, 0);
	}

	private @Nullable IrOp findSyntheticLambdaConstructor(@NotNull IrBlock block,
	                                                      @NotNull IrOp comparator,
	                                                      int startIndex) {
		for (int index = startIndex; index < block.statements().size(); index++) {
			IrStmt statement = block.statements().get(index);
			if (!(statement instanceof IrOp candidate)
					|| !(candidate.payload() instanceof InvokeInstruction instruction)
					|| !isConstructorInvoke(instruction) || candidate.inputs().isEmpty()) continue;
			if (candidate.inputs().getFirst().canonical() == comparator) return candidate;
		}
		return null;
	}

	private @Nullable IrOp syntheticLambdaConsumer(@NotNull IrOp comparator) {
		IrOp result = null;
		JvmLambdaMetadata.Target target = lambdaTarget(comparator);
		if (target == null) return null;
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				if (!(statement instanceof IrOp candidate)
						|| !(candidate.payload() instanceof InvokeInstruction instruction)
						|| !lambdaConsumer(instruction, target.kind(), lambdaInputIndex(target.kind()))
						|| candidate.inputs().size() < 2
						|| candidate.inputs().get(lambdaInputIndex(target.kind())).canonical() != comparator) continue;
				if (result != null) return null;
				result = candidate;
			}
		}
		return result;
	}

	private static int lambdaInputIndex(@NotNull JvmLambdaMetadata.Kind kind) {
		return kind == JvmLambdaMetadata.Kind.COMPARATOR || kind == JvmLambdaMetadata.Kind.RUNNABLE ? 1 : 0;
	}

	private static boolean lambdaConsumer(@NotNull InvokeInstruction instruction,
	                                     @NotNull JvmLambdaMetadata.Kind kind, int inputIndex) {
		if (kind == JvmLambdaMetadata.Kind.COMPARATOR)
			return inputIndex == 1 && instruction.opcode() == Invoke.STATIC
					&& "java/util/Arrays".equals(instruction.owner().internalName())
					&& "sort".equals(instruction.name())
					&& "([Ljava/lang/Object;Ljava/util/Comparator;)V".equals(instruction.type().descriptor());
		if (inputIndex == 0 && instruction.opcode() == Invoke.STATIC
				&& "java/util/Comparator".equals(instruction.owner().internalName())
				&& "comparing".equals(instruction.name())
				&& "(Ljava/util/function/Function;Ljava/util/Comparator;)Ljava/util/Comparator;".equals(instruction.type().descriptor()))
			return true;
		return kind == JvmLambdaMetadata.Kind.RUNNABLE
				&& inputIndex == 1
				&& (instruction.opcode() == Invoke.VIRTUAL || instruction.opcode() == Invoke.INTERFACE)
				&& ("java/util/concurrent/Executor".equals(instruction.owner().internalName())
						|| "java/util/concurrent/ExecutorService".equals(instruction.owner().internalName()))
				&& "execute".equals(instruction.name())
				&& "(Ljava/lang/Runnable;)V".equals(instruction.type().descriptor());
	}

	private @Nullable JvmLambdaMetadata.Target lambdaTarget(@NotNull IrOp comparator) {
		if (!(comparator.payload() instanceof NewInstanceInstruction instruction)) return null;
		JvmLambdaMetadata.Target metadataTarget = lambdaMetadata.target(instruction.type().internalName());
		if (metadataTarget != null) return metadataTarget;
		if (!"com/example/imageserver/transfer/IdentityStore$$ExternalSyntheticLambda".equals(
				instruction.type().internalName().substring(0, Math.min(instruction.type().internalName().length(),
						"com/example/imageserver/transfer/IdentityStore$$ExternalSyntheticLambda".length())))) return null;
		String syntheticName = instruction.type().internalName();
		String marker = "$$ExternalSyntheticLambda";
		int markerIndex = syntheticName.indexOf(marker);
		if (markerIndex < 0 || !method.source().getOwner().internalName().equals(syntheticName.substring(0, markerIndex)))
			return null;
		String suffix = syntheticName.substring(markerIndex + marker.length());
		if (suffix.isEmpty()) return null;
		String targetName = "lambda$" + method.source().getName() + "$" + suffix;
		// The synthetic comparator's DEX body is the proof source for this
		// convention: it delegates compare(Object,Object) to the static lambda
		// method on the enclosing class.  The target descriptor is fixed by the
		// comparator's two byte-array casts and integer result.
		return new JvmLambdaMetadata.Target(JvmLambdaMetadata.Kind.COMPARATOR,
				method.source().getOwner().internalName(), targetName, "([B[B)I", Invoke.STATIC);
	}

	private static boolean isStringBuilder(@NotNull IrValue value) {
		return "Ljava/lang/StringBuilder;".equals(value.type().descriptor());
	}

	private boolean isLoopBlock(@Nullable IrBlock block) {
		if (block == null) return false;
		for (IrBlock successor : block.successors())
			if (canReach(successor, block, new HashSet<>())) return true;
		return false;
	}

	private static boolean canReach(@NotNull IrBlock current, @NotNull IrBlock target,
	                               @NotNull Set<IrBlock> visited) {
		if (current == target) return true;
		if (!visited.add(current)) return false;
		for (IrBlock successor : current.successors())
			if (canReach(successor, target, visited)) return true;
		return false;
	}

	private @Nullable IrStmt constructedReceiverConsumer(@NotNull IrOp receiver, @NotNull IrOp constructor) {
		IrStmt consumer = null;
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				if (statement == constructor || !usesValue(statement, receiver)) continue;
				if (consumer != null) return null;
				consumer = statement;
			}
			IrTerminator terminator = block.terminator();
			if (terminator != null && usesValue(terminator, receiver)) {
				if (consumer != null) return null;
				consumer = terminator;
			}
		}
		return consumer;
	}

	private static boolean usesValue(@NotNull IrStmt statement, @NotNull IrValue value) {
		List<IrValue> inputs = switch (statement) {
			case IrOp op -> op.canonical() == op ? op.inputs() : List.of();
			case IrEffect effect -> effect.inputs();
			case IrTerminator terminator -> terminator.inputs();
		};
		for (IrValue input : inputs)
			if (input.canonical() == value.canonical()) return true;
		return false;
	}

	private boolean shouldInlineConstructedReceiverConstructor(@NotNull IrOp op) {
		if (!(op.payload() instanceof InvokeInstruction instruction) || !isConstructorInvoke(instruction)
				|| op.inputs().isEmpty()) return false;
		IrValue receiver = op.inputs().getFirst().canonical();
		boolean result = receiver instanceof IrOp receiverOp && inlineConstructedReceivers.contains(receiverOp)
				&& constructedReceiverConsumer(receiverOp, op) != null
				&& optimizationGuards.allowConstructorChain(op, constructedReceiverConsumer(receiverOp, op));
		return result;
	}

	private boolean canInlineConstructedReceiver(@NotNull IrOp constructor, @NotNull IrStmt consumer) {
		IrBlock sourceBlock = blockContaining(constructor);
		IrBlock consumerBlock = blockContaining(consumer);
		if (sourceBlock == null || consumerBlock == null) return false;
		int sourceIndex = method.blocks().indexOf(sourceBlock);
		int consumerBlockIndex = method.blocks().indexOf(consumerBlock);
		int constructorIndex = sourceBlock.statements().indexOf(constructor);
		if (sourceBlock == consumerBlock) {
			int consumerIndex = consumer instanceof IrTerminator
					? sourceBlock.statements().size() : sourceBlock.statements().indexOf(consumer);
			if (constructorIndex < 0 || consumerIndex <= constructorIndex) return false;
			for (int i = constructorIndex + 1; i < consumerIndex; i++)
				if (!shouldSkipSeparateEmission(sourceBlock.statements(), i, sourceBlock.terminator())) return false;
			return true;
		}
		return false;
	}

	private @Nullable IrBlock blockContaining(@NotNull IrStmt statement) {
		for (IrBlock block : method.blocks()) {
			if (block.statements().contains(statement) || block.terminator() == statement) return block;
		}
		return null;
	}

	private boolean isConstructorReceiverPair(@NotNull IrOp producer, IrStmt consumer) {
		if (!(producer.payload() instanceof NewInstanceInstruction))
			return false;
		if (!(consumer instanceof IrOp op))
			return false;
		if (!(op.payload() instanceof InvokeInstruction instruction) || !isConstructorInvoke(instruction))
			return false;
		return !op.inputs().isEmpty() && op.inputs().getFirst().canonical() == producer
				&& optimizationGuards.allowConstructorChain(op, consumer);
	}

	private boolean shouldDeferConstructedReceiver(@NotNull IrOp op) {
		if (!(op.payload() instanceof NewInstanceInstruction)) return false;
		IrOp constructor = constructorByReceiver.get(op);
		return constructor != null && optimizationGuards.allowConstructedReceiver(op, constructor);
	}

	private @Nullable IrOp constructedReceiver(@NotNull IrOp op) {
		if (!(op.payload() instanceof InvokeInstruction instruction) || !isConstructorInvoke(instruction) || op.inputs().isEmpty())
			return null;
		IrValue receiver = op.inputs().getFirst().canonical();
		if (receiver instanceof IrOp receiverOp && receiverOp.payload() instanceof NewInstanceInstruction)
			return receiverOp;
		return null;
	}

	private boolean shouldInlineConstructedReceiver(@NotNull IrOp op, @Nullable IrStmt next) {
		IrOp receiver = constructedReceiver(op);
		return receiver != null && inlineConstructedReceivers.contains(receiver) && consumesConstructedReceiver(next, receiver)
				&& next != null && optimizationGuards.allowConstructedReceiver(receiver, next);
	}

	private static boolean consumesConstructedReceiver(@Nullable IrStmt statement, @NotNull IrOp receiver) {
		if (statement instanceof IrTerminator terminator) {
			return (terminator.kind() == IrTerminatorKind.RETURN || terminator.kind() == IrTerminatorKind.THROW)
					&& !terminator.inputs().isEmpty()
					&& terminator.inputs().getFirst().canonical() == receiver;
		}
		if (!(statement instanceof IrOp op) || !(op.payload() instanceof InvokeInstruction instruction)
				|| isConstructorInvoke(instruction) || op.inputs().isEmpty())
			return false;
		return op.inputs().getFirst().canonical() == receiver;
	}

	private static boolean isReceiverReturningInvoke(@NotNull InvokeInstruction instruction) {
		return instruction.opcode() != Invoke.STATIC
				&& instruction.type().returnType().descriptor().equals(instruction.owner().descriptor());
	}

	private static boolean isReceiverReturningInvoke(@NotNull IrOp op) {
		return op.payload() instanceof InvokeInstruction instruction && isReceiverReturningInvoke(instruction);
	}

	private boolean tryEmitConstructAndPutChain(@NotNull List<IrStmt> statements, int index) {
		if (!(statements.get(index) instanceof IrOp invokeOp))
			return false;
		if (!(invokeOp.payload() instanceof InvokeInstruction invokeInstruction) || !isConstructorInvoke(invokeInstruction))
			return false;
		if (index + 1 >= statements.size())
			return false;
		if (!(statements.get(index + 1) instanceof IrEffect effect))
			return false;
		if (!optimizationGuards.allowConstructorChain(invokeOp, effect)) return false;
		if (invokeOp.inputs().isEmpty())
			return false;
		IrValue receiver = invokeOp.inputs().getFirst().canonical();
		if (!(receiver instanceof IrOp receiverOp))
			return false;
		if (!(receiverOp.payload() instanceof NewInstanceInstruction newInstanceInstruction))
			return false;
		if (useCount(receiverOp) != 2)
			return false;

		IrStmt previousStatement = currentStatement;
		currentStatement = invokeOp;
		switch (effect.payload()) {
			case InstanceFieldInstruction fieldInstruction -> {
				if (effect.inputs().size() < 2 || effect.inputs().get(1).canonical() != receiverOp) {
					currentStatement = previousStatement;
					return false;
				}
				load(effect.inputs().getFirst(), fieldInstruction.owner());
				mv.visitTypeInsn(NEW, newInstanceInstruction.type().internalName());
				mv.visitInsn(DUP);
				for (int inputIndex = 1; inputIndex < invokeOp.inputs().size(); inputIndex++) {
					load(invokeOp.inputs().get(inputIndex), semanticInputType(invokeOp, inputIndex));
				}
				mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(invokeInstruction.owner()),
						invokeInstruction.name(), invokeInstruction.type().descriptor(), false);
				mv.visitFieldInsn(PUTFIELD, fieldInstruction.owner().internalName(), fieldInstruction.name(), fieldInstruction.type().descriptor());
			}
			case StaticFieldInstruction fieldInstruction -> {
				if (effect.inputs().isEmpty() || effect.inputs().getFirst().canonical() != receiverOp) {
					currentStatement = previousStatement;
					return false;
				}
				mv.visitTypeInsn(NEW, newInstanceInstruction.type().internalName());
				mv.visitInsn(DUP);
				for (int inputIndex = 1; inputIndex < invokeOp.inputs().size(); inputIndex++) {
					load(invokeOp.inputs().get(inputIndex), semanticInputType(invokeOp, inputIndex));
				}
				mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(invokeInstruction.owner()),
						invokeInstruction.name(), invokeInstruction.type().descriptor(), false);
				mv.visitFieldInsn(PUTSTATIC, fieldInstruction.owner().internalName(), fieldInstruction.name(), fieldInstruction.type().descriptor());
			}
			case null, default -> {
				currentStatement = previousStatement;
				return false;
			}
		}
		currentStatement = previousStatement;
		return true;
	}

	private boolean tryEmitConstructAndPutChainAcrossBlocks(@NotNull List<IrStmt> statements, int index) {
		if (!policy.aggressiveCleanup()
				|| !optimizationPlan.featureEnabled(JvmOptimizationFeature.RECEIVER_CHAINS)
				|| !(statements.get(index) instanceof IrOp receiverOp)
				|| !(receiverOp.payload() instanceof NewInstanceInstruction newInstanceInstruction)
				|| useCount(receiverOp) != 2) return false;
		IrOp constructor = constructorByReceiver.get(receiverOp);
		if (constructor == null || !(constructor.payload() instanceof InvokeInstruction invokeInstruction)
				|| !isConstructorInvoke(invokeInstruction)) return false;
		IrStmt consumer = constructedReceiverConsumer(receiverOp, constructor);
		if (!(consumer instanceof IrEffect effect) || !(effect.payload() instanceof InstanceFieldInstruction fieldInstruction)
				|| effect.inputs().size() < 2 || effect.inputs().get(1).canonical() != receiverOp
				|| !optimizationGuards.safeStatement(constructor) || !optimizationGuards.safeStatement(effect)) return false;
		IrBlock allocationBlock = blockContaining(receiverOp);
		IrBlock constructorBlock = blockContaining(constructor);
		IrBlock effectBlock = blockContaining(effect);
		if (allocationBlock == null || constructorBlock == null || effectBlock == null
				|| allocationBlock.statements().size() != 1
				|| constructorBlock.statements().size() != 1
				|| effectBlock.statements().size() != 1
				|| !cleanupTailRangeProfile(allocationBlock).equals(cleanupTailRangeProfile(constructorBlock))
				|| !cleanupTailRangeProfile(allocationBlock).equals(cleanupTailRangeProfile(effectBlock))
				|| !linearMaterializationPath(allocationBlock, constructorBlock)
				|| !linearMaterializationPath(constructorBlock, effectBlock)) return false;

		IrStmt previousStatement = currentStatement;
		currentStatement = constructor;
		load(effect.inputs().getFirst(), fieldInstruction.owner());
		mv.visitTypeInsn(NEW, newInstanceInstruction.type().internalName());
		mv.visitInsn(DUP);
		for (int inputIndex = 1; inputIndex < constructor.inputs().size(); inputIndex++)
			load(constructor.inputs().get(inputIndex), semanticInputType(constructor, inputIndex));
		mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(invokeInstruction.owner()),
				invokeInstruction.name(), invokeInstruction.type().descriptor(), false);
		mv.visitFieldInsn(PUTFIELD, fieldInstruction.owner().internalName(), fieldInstruction.name(),
				fieldInstruction.type().descriptor());
		currentStatement = previousStatement;
		emittedOps.add(receiverOp);
		emittedOps.add(constructor);
		emittedEffects.add(effect);
		return true;
	}

	private boolean linearMaterializationPath(@NotNull IrBlock source, @NotNull IrBlock target) {
		if (source == target) return true;
		Set<IrBlock> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		IrBlock cursor = source;
		while (cursor != target) {
			if (!visited.add(cursor) || cursor.successors().size() != 1) return false;
			IrBlock next = cursor.successors().getFirst();
			if (next != target && (!next.phis().isEmpty() || next.exceptionValue() != null
					|| !next.exceptionalSuccessors().isEmpty() || !next.statements().isEmpty()
					|| next.terminator() == null || next.terminator().kind() != IrTerminatorKind.GOTO)) return false;
			cursor = next;
		}
		return true;
	}

	private void emitConstructAndPut(@NotNull IrOp invokeOp, @NotNull InvokeInstruction invokeInstruction,
	                                 @NotNull NewInstanceInstruction newInstanceInstruction,
	                                 @NotNull InstanceFieldInstruction fieldInstruction) {
		mv.visitTypeInsn(NEW, newInstanceInstruction.type().internalName());
		mv.visitInsn(DUP);
		for (int inputIndex = 1; inputIndex < invokeOp.inputs().size(); inputIndex++)
			load(invokeOp.inputs().get(inputIndex), semanticInputType(invokeOp, inputIndex));
		mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(invokeInstruction.owner()),
				invokeInstruction.name(), invokeInstruction.type().descriptor(), false);
		mv.visitFieldInsn(PUTFIELD, fieldInstruction.owner().internalName(), fieldInstruction.name(), fieldInstruction.type().descriptor());
	}

	private void emitConstructAndPut(@NotNull IrOp invokeOp, @NotNull InvokeInstruction invokeInstruction,
	                                 @NotNull NewInstanceInstruction newInstanceInstruction,
	                                 @NotNull StaticFieldInstruction fieldInstruction) {
		mv.visitTypeInsn(NEW, newInstanceInstruction.type().internalName());
		mv.visitInsn(DUP);
		for (int inputIndex = 1; inputIndex < invokeOp.inputs().size(); inputIndex++)
			load(invokeOp.inputs().get(inputIndex), semanticInputType(invokeOp, inputIndex));
		mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(invokeInstruction.owner()),
				invokeInstruction.name(), invokeInstruction.type().descriptor(), false);
		mv.visitFieldInsn(PUTSTATIC, fieldInstruction.owner().internalName(), fieldInstruction.name(), fieldInstruction.type().descriptor());
	}

	private int tryEmitArrayStaticPutChain(@NotNull List<IrStmt> statements, int index) {
		if (!(statements.get(index) instanceof IrOp arrayOp))
			return 0;
		if (!(arrayOp.payload() instanceof NewArrayInstruction newArrayInstruction))
			return 0;
		IrValue arrayValue = arrayOp.canonical();

		int cursor = index + 1;
		int expectedIndex = 0;
		List<IrEffect> arrayStores = new ArrayList<>();
		while (cursor < statements.size()) {
			while (cursor < statements.size() && shouldSkipSeparateEmission(statements, cursor)) {
				cursor++;
			}
			if (cursor >= statements.size())
				return 0;
			if (!(statements.get(cursor) instanceof IrEffect effect))
				break;
			if (!(effect.payload() instanceof ArrayInstruction))
				break;
			if (effect.inputs().size() < 3 || effect.inputs().get(0).canonical() != arrayValue)
				return 0;
			Object indexValue = effect.inputs().get(1).canonical().constantValue();
			if (!(indexValue instanceof Number number) || number.intValue() != expectedIndex)
				return 0;
			arrayStores.add(effect);
			expectedIndex++;
			cursor++;
		}
		while (cursor < statements.size() && shouldSkipSeparateEmission(statements, cursor)) {
			cursor++;
		}
		if (cursor == index + 1 || cursor >= statements.size())
			return 0;
		if (!(statements.get(cursor) instanceof IrEffect putEffect))
			return 0;
		if (!(putEffect.payload() instanceof StaticFieldInstruction fieldInstruction))
			return 0;
		if (putEffect.inputs().isEmpty() || putEffect.inputs().getFirst().canonical() != arrayValue)
			return 0;
		if (useCount(arrayOp) != expectedIndex + 1)
			return 0;
		if (!optimizationGuards.allowArrayChain(arrayOp, arrayStores, putEffect)) return 0;

		IrStmt previousStatement = currentStatement;
		currentStatement = arrayOp;
		load(arrayOp.inputs().getFirst(), Types.INT);
		ConversionSupport.emitNewArray(mv, newArrayInstruction.componentType());
		for (IrEffect effect : arrayStores) {
			currentStatement = effect;
			mv.visitInsn(DUP);
			load(effect.inputs().get(1), Types.INT);
			ClassType elementType = effect.semantics().inputs().get(0).expected().materializedType()
					instanceof ArrayType array ? array.componentType() : effect.semantics().inputs().get(2).expected().materializedType();
			load(effect.inputs().get(2), effect.semantics().inputs().get(2).expected().materializedType());
			mv.visitInsn(ConversionSupport.arrayStoreOpcode(elementType));
		}
		currentStatement = putEffect;
		mv.visitFieldInsn(PUTSTATIC, fieldInstruction.owner().internalName(), fieldInstruction.name(), fieldInstruction.type().descriptor());
		currentStatement = previousStatement;
		return cursor - index + 1;
	}

	private void emitStatement(@NotNull IrStmt statement) {
		IrStatementEmitter.emit(statement,
				op -> emitOp(op, shouldStoreResult(op) ? IrOperationEmitter.ResultMode.STORE : IrOperationEmitter.ResultMode.DISCARD),
				this::emitEffect);
	}

	private void emitTerminator(@NotNull IrBlock block) {
		IrTerminator terminator = block.terminator();
		if (terminator == null) return;
		switch (terminator.kind()) {
			case GOTO -> {
				IrBlock target = gotoTarget(block, terminator.payload());
				if (target != null) emitEdge(block, target, true);
			}
			case IF -> emitIf(block, terminator, (BranchInstruction) terminator.payload(), terminator.inputs());
			case IF_ZERO ->
					emitIfZero(block, terminator, (BranchZeroInstruction) terminator.payload(), terminator.inputs().getFirst());
			case SWITCH -> emitSwitch(block, terminator);
			case RETURN -> emitReturn(terminator, (ReturnInstruction) terminator.payload(), terminator.inputs());
			case THROW -> {
				// ATHROW requires a reference regardless of the imprecise source type.
				// Invalid primitive values are materialized as null and reported by load().
				load(terminator.inputs().getFirst(), terminatorInputType(terminator, 0));
				mv.visitInsn(ATHROW);
			}
		}
	}

	private void emitTryCatches() {
		for (JvmExceptionLayoutPlan.PlannedRange range : exceptionLayoutPlan.plannedRanges()) {
			Label plannedStart = exceptionLayoutPlan.tryStart(range.region());
			boolean concreteCatch = isIoCatch(range.handler().handler())
					&& directHandlerEntries.contains(range.handler().handlerBlock());
			Label defaultStart = range.usesHandlerStub()
					? labels.get(range.firstSource())
					: (plannedStart == null
							? (policy.aggressiveCleanup() && concreteCatch ? labels.get(range.firstSource())
									: labelAtOrEnd(range.region().startOffset()))
							: plannedStart);
			Label start = range.primaryExceptionState() == null
					? protectedRangeStart(range.region(), range.handler(), range.firstSource(), defaultStart)
					: range.primaryExceptionState().afterStore();
			Label end = range.end();
			if (!instructionTracker.hasInstructionBetween(start, end)) {
				report(ConversionDiagnostic.Kind.VERIFIER, range.region().startOffset(),
						"Suppressed an exception range with no emitted JVM instructions");
				continue;
			}
			mv.visitTryCatchBlock(start, end, range.handlerLabel(), range.catchType());
		}
	}

	private void emitPrimaryExceptionBoundaries(@NotNull IrBlock block) {
		if (exceptionLayoutPlan == null) return;
		for (JvmExceptionLayoutPlan.PlannedRange range : exceptionLayoutPlan.plannedRanges()) {
			JvmExceptionLayoutPlan.PrimaryExceptionState state = range.primaryExceptionState();
			if (state == null || state.entry() != block
					|| !emittedPrimaryExceptionBoundaries.add(state.afterStore())) continue;
			mv.visitLabel(state.afterStore());
		}
	}

	private @NotNull Label protectedRangeStart(@NotNull IrExceptionRegion region,
	                                           @NotNull IrExceptionHandler exceptionHandler,
	                                           @NotNull IrBlock firstSource,
	                                           @NotNull Label defaultStart) {
		Handler handler = exceptionHandler.handler();
		if (handler != null && !handler.isCatchAll()) return defaultStart;
		JvmCleanupRegionPlan lifecycle = lifecycleForRange(region);
		if (lifecycle == null || lifecycle.resource() == null) return defaultStart;
		IrBlock initializer = layout.emissionOrder().stream()
				.filter(candidate -> candidate != firstSource
						&& candidate.successors().contains(firstSource)
						&& candidate.terminator() != null
						&& candidate.terminator().kind() == IrTerminatorKind.IF_ZERO
						&& candidate.statements().isEmpty())
				.findFirst().orElse(null);
		if (initializer == null || initializer.terminator().inputs().isEmpty()
				|| initializer.terminator().inputs().getFirst().canonical() != lifecycle.resource().canonical())
			return defaultStart;
		Label boundary = protectedBoundaryLabels.get(initializer);
		Label start = boundary == null ? labels.get(initializer) : boundary;
		return start == null || !instructionTracker.hasInstructionBetween(start, defaultStart) ? defaultStart : start;
	}


	/**
	 * A DEX frontend may split one protected source sequence after every
	 * throwing instruction.  When all intervening emitted blocks are part of
	 * the same source region, those splits do not represent distinct JVM
	 * handlers and can be covered by one contiguous exception-table range.
	 * This is deliberately independent of decompiler shape and never crosses
	 * emitted code outside the IR region.
	 */
	private boolean canCoalesceProtectedRange(@NotNull IrExceptionRegion region,
	                                         @NotNull IrBlock previous,
	                                         @NotNull IrBlock next) {
		int previousIndex = layout.emissionOrder().indexOf(previous);
		int nextIndex = layout.emissionOrder().indexOf(next);
		if (previousIndex < 0 || nextIndex <= previousIndex) return false;
		Set<IrBlock> protectedBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
		protectedBlocks.addAll(region.protectedBlocks());
		for (int index = previousIndex + 1; index < nextIndex; index++) {
			IrBlock block = layout.emissionOrder().get(index);
			if (protectedBlocks.contains(block)) continue;
			if (exceptionLayoutPlan.skipped(block) || isTransparentBlock(block)) continue;
			return false;
		}
		return true;
	}

	private boolean isSyntheticRethrowRegion(@NotNull IrExceptionRegion region,
	                                         @NotNull IrExceptionHandler exceptionHandler) {
		if (!policy.aggressiveCleanup())
			return IrExceptionEmitter.isSyntheticRethrowRegion(method, region, exceptionHandler);
		if (!IrExceptionEmitter.isSyntheticRethrowRegion(method, region, exceptionHandler)) return false;
		return cleanupPlans.values().stream().anyMatch(parent -> parent.region() != region
				&& relocatedNullResourceRegions.contains(region)
				&& parent.handler().handlerBlock() == exceptionHandler.handlerBlock()
				&& parent.region().startOffset() <= region.startOffset()
				&& parent.region().endOffset() >= region.endOffset()
				&& parent.normalClose() != null
				&& parent.hasSuppressedExceptionPath());
	}

	private boolean isRedundantNullResourceRegion(@NotNull IrExceptionRegion region,
	                                              @NotNull IrExceptionHandler exceptionHandler) {
		if (!policy.aggressiveCleanup()) return isLegacyRedundantNullResourceRegion(region, exceptionHandler);
		// The selected lifecycle plan may describe the enclosing resource region
		// itself.  Its null path must not suppress that enclosing range; otherwise
		// the close/rethrow handler is emitted as an orphaned JVM label.  Retain
		// only the narrower structural predicate for genuinely nested synthetic
		// null-resource regions.
		return cleanupPlans.values().stream().anyMatch(parent -> parent.region() != region
				&& relocatedNullResourceRegions.contains(region)
				&& parent.handler().handlerBlock() == exceptionHandler.handlerBlock()
				&& parent.region().startOffset() <= region.startOffset()
				&& parent.region().endOffset() >= region.endOffset()
				&& parent.hasNullResourcePath()
				&& nullPathMatchesResource(region, parent.resource())
				&& isLegacyRedundantNullResourceRegion(region, exceptionHandler));
	}

	private boolean nullPathMatchesResource(@NotNull IrExceptionRegion region, @NotNull IrValue resource) {
		IrValue canonical = resource.canonical();
		for (IrBlock block : region.protectedBlocks()) {
			IrTerminator terminator = block.terminator();
			if (terminator == null || terminator.kind() != IrTerminatorKind.IF_ZERO
					|| terminator.inputs().isEmpty()) continue;
			IrValue input = terminator.inputs().getFirst().canonical();
			if (input == canonical) return true;
			if (canonical instanceof IrPhi phi && phi.operands().values().stream()
					.map(IrValue::canonical).anyMatch(value -> value == input)) return true;
		}
		return false;
	}

	private boolean isLegacyRedundantNullResourceRegion(@NotNull IrExceptionRegion region,
	                                                    @NotNull IrExceptionHandler exceptionHandler) {
		return IrExceptionEmitter.isRedundantNullResourceRegion(region, exceptionHandler, blockByOffset);
	}

	private void deferNullThrowBlocks(@NotNull IrExceptionRegion region,
	                                 @NotNull List<IrBlock> protectedBody) {
		IrExceptionRegion outer = method.exceptionRegions().stream()
				.filter(candidate -> candidate != region && candidate.endOffset() < region.startOffset()
						&& candidate.handlers().stream().anyMatch(handler -> region.handlers().stream()
							.anyMatch(inner -> handler.handlerBlock() == inner.handlerBlock())))
				.max(Comparator.comparingInt(IrExceptionRegion::endOffset))
				.orElse(null);
		if (outer == null) return;
		List<IrBlock> blocks = new ArrayList<>(protectedBody);
		blocks.sort(Comparator.comparingInt(IrBlock::startOffset));
		IrBlock trySource = method.blocks().stream()
				.filter(candidate -> region.protectedBlocks().contains(candidate)
						&& candidate.terminator() != null
						&& candidate.terminator().kind() == IrTerminatorKind.IF_ZERO
						&& candidate.terminator().payload() instanceof BranchZeroInstruction branch
						&& blockByOffset.get(branch.label().position()) == blocks.getFirst())
				.findFirst().orElse(null);
		if (trySource == null) return;
		IrBlock insertion = nextBlock(trySource);
		if (insertion == null) return;
		Label start = tryStartLabels.computeIfAbsent(outer, ignored -> new Label());
		tryStartLabelsByBlock.put(trySource, start);
		deferredNullThrowBlocks.addAll(blocks);
		deferredNullThrowInsertions.computeIfAbsent(insertion, ignored -> new ArrayList<>()).addAll(blocks);
		relocatedNullResourceRegions.add(region);
	}

	private void emitHandlerStubs() {
		// JVM handlers enter with the exception on the operand stack, while the IR
		// models it as a value in a handler block.  These labels bridge the two
		// representations and copy any handler-phi values before entering the real
		// handler body.  Without them, a split DEX try range can leave an invalid
		// stack/local shape for the verifier and decompiler.
		for (Map.Entry<IrBlock, Label> entry : simpleHandlerStubLabels.entrySet()) {
			if (canonicalCompositePlan() != null && !plannedHandlerUsesLabel(entry.getValue())) continue;
			IrBlock target = entry.getKey();
			mv.visitLabel(entry.getValue());
			if (target.exceptionValue() != null)
				store(target.exceptionValue());
			else
				mv.visitInsn(POP);
			IrBlock representative = sharedHandlerStubSources.get(target);
			if (representative != null) {
				currentBlock = representative;
				emitPhiCopies(representative, target);
			}
			mv.visitJumpInsn(GOTO, labels.get(target));
		}
		for (Map.Entry<JvmHandlerStubKey, Label> entry : handlerStubLabels.entrySet()) {
			if (canonicalCompositePlan() != null && !plannedHandlerUsesLabel(entry.getValue())) continue;
			JvmHandlerStubKey key = entry.getKey();
			IrBlock target = key.target();
			currentBlock = key.source();
			mv.visitLabel(entry.getValue());
			if (target.exceptionValue() != null) {
				store(target.exceptionValue());
			} else {
				mv.visitInsn(POP);
			}
			emitPhiCopies(key.source(), target);
			mv.visitJumpInsn(GOTO, handlerEntryLabel(target));
		}
	}

	private boolean plannedHandlerUsesLabel(@NotNull Label label) {
	if (exceptionLayoutPlan == null) return false;
	return exceptionLayoutPlan.plannedRanges().stream()
			.anyMatch(range -> range.usesHandlerStub() && range.handlerLabel() == label);
	}


	private void emitHandlerTails() {
		for (HandlerTail tail : handlerTails.values()) {
			mv.visitLabel(tail.label());
			store(tail.root().exceptionValue());
			emitPhiCopies(tail.root(), tail.target());
			emitTailBlock(tail.target());
		}
	}

	private void emitDeferredNormalTails() {
		for (IrBlock block : method.blocks()) {
			if (deferredNormalTailBlocks.contains(block)) {
				mv.visitLabel(labels.get(block));
				emittedBlockLabels.add(block);
				emitTailBlock(block);
			}
		}
	}

	private void emitSharedCleanupTails() {
		for (JvmCleanupTailPlan plan : cleanupTailPlans) {
			Label label = cleanupTailLabels.get(plan);
			if (label == null) continue;
			mv.visitLabel(label);
			IrBlock canonical = plan.canonical().entry();
			currentBlock = canonical;
			emitTailBlock(canonical);
		}
	}

	private void emitSharedMonitorExitTails() {
		for (JvmMonitorRegionPlan plan : monitorRegionPlans) {
			Label label = monitorExitLabels.get(plan);
			if (label == null) continue;
			mv.visitLabel(label);
			currentBlock = plan.canonicalExit();
			emitTailBlock(plan.canonicalExit());
		}
	}

	private boolean emitInPlaceSkippedAlias(@NotNull IrBlock block) {
		if (isTransparentBlock(block) || emittedBlockLabels.contains(block)
				|| !referencedBlockTargets.contains(block)) return false;
		if (hasEmittedSharedLabel(block)) return true;
		Label target = skippedBlockAliasTarget(block, Collections.newSetFromMap(new IdentityHashMap<>()));
		if (target == null || target == labels.get(block)) return false;
		mv.visitLabel(labels.get(block));
		emittedBlockLabels.add(block);
		mv.visitJumpInsn(GOTO, target);
		return true;
	}

	private void emitDeferredNullThrowBlock(@NotNull IrBlock block) {
		// Emit the relocated null branch with its normal label and statements.  Its
		// original IR successors are preserved, so only bytecode layout changes.
		mv.visitLabel(labels.get(block));
		emittedBlockLabels.add(block);
		emitTailBlock(block);
	}

	/**
	 * Skipping a lowering block is only valid when every bytecode reference to
	 * its original label is redirected.  DEX block order can still leave a
	 * branch or a switch edge targeting the original label, however, especially
	 * for handler-tail and transparent-goto cleanup.  Emit a zero-stack alias
	 * for those labels after the selected tails have been materialized.  This
	 * keeps branch targets present in the MethodNode while preserving the
	 * planned cleanup/handler destination.
	 */
	private void emitSkippedBlockAliases() {
		List<Map.Entry<IrBlock, Label>> aliases = new ArrayList<>();
		for (IrBlock block : method.blocks()) {
			boolean transparent = policy.aggressiveCleanup()
					? layoutTransparentBlocks.contains(block) : isTransparentBlock(block);
			if (emittedBlockLabels.contains(block)
					|| !exceptionLayoutPlan.skipped(block) && !transparent) continue;
			if (hasEmittedSharedLabel(block)) continue;
			Label target = skippedBlockAliasTarget(block, Collections.newSetFromMap(new IdentityHashMap<>()));
			if (!referencedBlockTargets.contains(block) || target == null || target == labels.get(block)) continue;
			aliases.add(Map.entry(block, target));
		}
		if (aliases.isEmpty()) return;
		// The ordinary fall-through path must reach the canonical end label, not
		// enter a bridge that is present only for an explicit branch edge.
		mv.visitJumpInsn(GOTO, endLabel);
		for (Map.Entry<IrBlock, Label> alias : aliases) {
			mv.visitLabel(labels.get(alias.getKey()));
			mv.visitJumpInsn(GOTO, alias.getValue());
		}
	}

	private boolean hasEmittedSharedLabel(@NotNull IrBlock block) {
		Label label = labels.get(block);
		return label != null && emittedBlockLabels.stream()
				.anyMatch(emitted -> labels.get(emitted) == label);
	}

	private @Nullable Label skippedBlockAliasTarget(@NotNull IrBlock block,
	                                                @NotNull Set<IrBlock> visited) {
		if (!visited.add(block)) return null;
		Label plannedTail = plannedTailLabel(block);
		if (plannedTail != null) return plannedTail;
		if (isTransparentBlock(block) && block.successors().size() == 1) {
			IrBlock successor = block.successors().getFirst();
			if (emittedBlockLabels.contains(successor)) return labels.get(successor);
			return skippedBlockAliasTarget(successor, visited);
		}
		return null;
	}

	private @Nullable Label plannedTailLabel(@NotNull IrBlock block) {
		JvmCleanupTailPlan cleanupTail = sharedCleanupTails.get(block);
		if (cleanupTail != null) return cleanupTailLabels.get(cleanupTail);
		JvmMonitorRegionPlan monitorTail = sharedMonitorExitBlocks.get(block);
		if (monitorTail != null) return monitorExitLabels.get(monitorTail);
		for (HandlerTail tail : handlerTails.values())
			if (tail.root() == block || tail.target() == block) return tail.label();
		return null;
	}

	private @NotNull Label branchTargetLabel(@NotNull IrBlock block) {
		referencedBlockTargets.add(block);
		return labels.get(block);
	}

	private void emitTailBlock(@NotNull IrBlock block) {
		blockEmitter.emitBody(block, blockEmitterHost());
	}

	private @NotNull Label handlerEntryLabel(@NotNull IrBlock handler) {
		return exceptionLayoutPlan.handlerEntry(handler);
	}


	private Label labelAtOrEnd(int offset) {
		IrBlock block = blockByOffset.get(offset);
		return block == null ? endLabel : labels.get(block);
	}

	private @NotNull Label protectedEndLabel(@NotNull IrBlock block, int effectiveTryCatchEnd) {
		Label boundary = protectedBoundaryLabels.get(block);
		if (boundary != null) return boundary;
		return labelAtOrEnd(Math.min(emittedBlockEndOffset(block), effectiveTryCatchEnd));
	}

	/**
	 * Source offsets are not JVM layout offsets after aggressive block shaping.
	 * Find the first emitted label after the final protected source block so a
	 * valid range is not accidentally collapsed onto its start label.
	 */
	private @NotNull Label aggressiveProtectedEnd(@NotNull IrBlock lastSource, int effectiveTryCatchEnd) {
		Label boundary = protectedBoundaryLabels.get(lastSource);
		if (boundary != null) return boundary;
		List<IrBlock> emission = layout.emissionOrder();
		int index = emission.indexOf(lastSource);
		if (index >= 0) {
			Label start = labels.get(lastSource);
			for (int cursor = index + 1; cursor < emission.size(); cursor++) {
				IrBlock candidate = emission.get(cursor);
				Label label = labels.get(candidate);
				if (label == null || label == start) continue;
				if (instructionTracker.hasInstructionBetween(start, label)) return label;
			}
		}
		return labelAtOrEnd(Math.min(emittedBlockEndOffset(lastSource), effectiveTryCatchEnd));
	}

	private int blockEndOffset(@NotNull IrBlock block) {
		IrBlock next = nextBlock(block);
		return next == null ? Integer.MAX_VALUE : next.startOffset();
	}

	private int emittedBlockEndOffset(@NotNull IrBlock block) {
		IrBlock next = nextBlock(block);
		while (next != null && isNonThrowingGlueBlock(next)) {
			next = nextBlock(next);
		}
		return next == null ? Integer.MAX_VALUE : next.startOffset();
	}

	private int effectiveTryCatchEndOffset(@NotNull IrExceptionRegion region) {
		int endOffset = region.endOffset();
		IrBlock boundary = blockByOffset.get(endOffset);
		while (boundary != null && isNonThrowingGlueBlock(boundary)) {
			IrBlock next = nextBlock(boundary);
			endOffset = next == null ? Integer.MAX_VALUE : next.startOffset();
			boundary = next;
		}
		return endOffset;
	}

	private @NotNull List<IrBlock> coveredSourceBlocks(@NotNull IrExceptionRegion region,
	                                                   @NotNull IrExceptionHandler handler) {
		List<IrBlock> sources = new ArrayList<>();
		for (IrBlock block : region.protectedBlocks()) {
			if (!block.exceptionalSuccessors().contains(handler.handlerBlock())) continue;
			if (isTransparentBlock(block) || block.dexInstructions().isEmpty()) continue;
			if (!hasEmittedProtectedCode(block)) continue;
			sources.add(block);
		}
		return sources;
	}

	private boolean hasEmittedProtectedCode(@NotNull IrBlock block) {
		if (skippedHandlerTailBlocks.contains(block) || deferredNormalTailBlocks.contains(block)
				|| deferredNullThrowBlocks.contains(block) || fullyInlinedReturnBlocks.contains(block)) return false;
		if (isTransparentBlock(block)) return false;
		if (hasEmittableStatements(block)) return true;
		return block.terminator() != null;
	}

	private void emitEdgeGoto(@NotNull IrBlock source, @NotNull IrBlock target) {
		JvmMonitorRegionPlan monitorPlan = exceptionLayoutPlan.monitorTail(target);
		if (monitorPlan != null) {
			emitSharedMonitorExitEdge(source, target, monitorPlan);
			return;
		}
		JvmCleanupTailPlan sharedTail = exceptionLayoutPlan.cleanupTail(target);
		if (sharedTail != null) {
			emitSharedCleanupTailEdge(source, target, sharedTail);
			return;
		}
		if (tryEmitDirectPhiReturn(source, target)) return;
		emitPhiCopies(source, target);
		mv.visitJumpInsn(GOTO, branchTargetLabel(target));
	}

	private void emitEdgeFallthrough(@NotNull IrBlock source, @NotNull IrBlock target) {
		JvmMonitorRegionPlan monitorPlan = exceptionLayoutPlan.monitorTail(target);
		if (monitorPlan != null) {
			emitSharedMonitorExitEdge(source, target, monitorPlan);
			return;
		}
		JvmCleanupTailPlan sharedTail = exceptionLayoutPlan.cleanupTail(target);
		if (sharedTail != null) {
			emitSharedCleanupTailEdge(source, target, sharedTail);
			return;
		}
		if (tryEmitDirectPhiReturn(source, target)) return;
		emitPhiCopies(source, target);
	}

	private void emitSharedCleanupTailEdge(@NotNull IrBlock source, @NotNull IrBlock target,
	                                      @NotNull JvmCleanupTailPlan plan) {
		Map<IrValue, IrValue> mapping = plan.edgeMappings().get(target);
		if (mapping != null) {
			for (Map.Entry<IrValue, IrValue> entry : mapping.entrySet()) {
				IrValue destination = entry.getKey().canonical();
				IrValue value = entry.getValue().canonical();
				if (!destination.hasLocal()) {
					report(ConversionDiagnostic.Kind.VERIFIER, currentOffset(),
							"Missing JVM local for cleanup-tail argument " + destination.id());
					continue;
				}
				load(value, destination.type());
				store(destination);
			}
		}
		mv.visitJumpInsn(GOTO, cleanupTailLabels.get(plan));
	}

	private void emitSharedMonitorExitEdge(@NotNull IrBlock source, @NotNull IrBlock target,
	                                      @NotNull JvmMonitorRegionPlan plan) {
		Map<IrValue, IrValue> mapping = plan.edgeMappings().get(target);
		if (mapping != null) {
			for (Map.Entry<IrValue, IrValue> entry : mapping.entrySet()) {
				IrValue destination = entry.getKey().canonical();
				IrValue value = entry.getValue().canonical();
				if (!destination.hasLocal()) {
					report(ConversionDiagnostic.Kind.VERIFIER, currentOffset(),
							"Missing JVM local for monitor cleanup argument " + destination.id());
					continue;
				}
				if (destination != value) {
					load(value, destination.type());
					store(destination);
				}
			}
		}
		mv.visitJumpInsn(GOTO, monitorExitLabels.get(plan));
	}


	private boolean isDirectPhiReturnOperand(@NotNull IrBlock source, @NotNull IrStmt statement) {
		if (!policy.optimized()) return false;
		if (source.exceptionValue() != null || !source.exceptionalSuccessors().isEmpty())
			return false;
		if (!(statement instanceof IrOp op) || op.canonical() != op)
			return false;
		IrBlock target = source.terminator() == null ? null : gotoTarget(source, source.terminator().payload());
		if (target == null || target.terminator() == null || target.terminator().kind() != IrTerminatorKind.RETURN)
			return false;
		IrValue input = target.terminator().inputs().isEmpty() ? null : target.terminator().inputs().getFirst().canonical();
		if (input instanceof IrOp inputOp) {
			return inputOp == op && directReturnOperands.contains(op);
		}
		return directReturnOperands.contains(op) && input instanceof IrPhi phi && phi.operands().get(source) != null
				&& phi.operands().get(source).canonical() == op;
	}

	private boolean tryEmitDirectPhiReturn(@NotNull IrBlock source, @NotNull IrBlock target) {
		DirectReturn directReturn = directReturn(source, target);
		if (directReturn == null) return false;
		IrValue canonicalOperand = directReturn.value().canonical();
		if (!optimizationGuards.allowDirectReturn(source, target, canonicalOperand)) return false;
		if (canonicalOperand instanceof IrOp op && useCount(op) != 1 && !emittedOps.contains(op)) return false;
		IrStmt previousStatement = currentStatement;
		currentStatement = directReturn.terminator();
		if (canonicalOperand instanceof IrOp op) {
			if (emittedOps.contains(op)) load(op, method.source().getType().returnType());
			else emitOp(op, IrOperationEmitter.ResultMode.LEAVE_ON_STACK);
		} else {
			load(canonicalOperand, method.source().getType().returnType());
		}
		emitReturnOpcode((ReturnInstruction) directReturn.terminator().payload());
		currentStatement = previousStatement;
		return true;
	}

	private boolean canEmitDirectPhiReturn(@NotNull IrBlock source, @NotNull IrBlock target) {
		DirectReturn directReturn = directReturn(source, target);
		if (directReturn == null) return false;
		IrValue operand = directReturn.value().canonical();
		return optimizationGuards.allowDirectReturn(source, target, operand)
				&& (!(operand instanceof IrOp op) || useCount(op) == 1);
	}

	private @Nullable DirectReturn directReturn(@NotNull IrBlock source, @NotNull IrBlock target) {
		if (source.exceptionValue() != null || !source.exceptionalSuccessors().isEmpty())
			return null;
		IrBlock current = target;
		IrBlock returnSource = source;
		Set<IrBlock> visited = new HashSet<>();
		while (visited.add(current)) {
			IrTerminator terminator = current.terminator();
			if (terminator == null) return null;
			if (terminator.kind() == IrTerminatorKind.RETURN) {
				if (!current.statements().isEmpty() || current.exceptionValue() != null
						|| !current.exceptionalSuccessors().isEmpty() || terminator.inputs().isEmpty()) return null;
				IrValue input = terminator.inputs().getFirst().canonical();
				IrValue operand = input instanceof IrPhi phi ? phi.operands().get(returnSource) : input;
				return operand == null ? null : new DirectReturn(terminator, operand.canonical());
			}
			if (!current.statements().isEmpty() || current.exceptionValue() != null
					|| !current.exceptionalSuccessors().isEmpty() || terminator.kind() != IrTerminatorKind.GOTO)
				return null;
			IrBlock next = gotoTarget(current, terminator.payload());
			if (next == null) return null;
			returnSource = current;
			current = next;
		}
		return null;
	}

	private @Nullable IrValue uniformPhiValue(@NotNull IrValue value, @NotNull Set<IrValue> visited) {
		IrValue canonical = value.canonical();
		if (!(canonical instanceof IrPhi phi)) return canonical;
		if (!visited.add(phi) || phi.operands().isEmpty()) return null;
		try {
			IrValue result = null;
			for (IrValue operand : phi.operands().values()) {
				IrValue resolved = uniformPhiValue(operand, visited);
				if (resolved == null) return null;
				if (result == null) {
					result = resolved;
				} else if (result.canonical() != resolved.canonical() && !sameConstant(result, resolved)) {
					return null;
				}
			}
			return result;
		} finally {
			visited.remove(phi);
		}
	}

	private void emitEdge(@NotNull IrBlock source, @NotNull IrBlock target, boolean allowFallthrough) {
		if (allowFallthrough && !deferredNormalTailBlocks.contains(source)
				&& !deferredNormalTailBlocks.contains(target) && target == nextBlock(source)) {
			emitEdgeFallthrough(source, target);
			return;
		}
		emitEdgeGoto(source, target);
	}

	private IrBlock nextBlock(@NotNull IrBlock block) {
		return layout.nextBlock(block);
	}

	private IrBlock plannedFallthrough(@NotNull IrBlock block) {
		JvmLoopShapePlan plan = loopShapeByBlock.get(block);
		if (plan != null && plan.accepted()) {
			IrBlock preferred = plan.preferredFallthrough().get(block);
			if (preferred != null) return preferred;
		}
		return nextBlock(block);
	}

	private boolean isTransparentBlock(@NotNull IrBlock block) {
		if (!block.phis().isEmpty()) {
			// In guarded mode a phi whose every incoming edge carries the same
			// proven value is not a JVM state transition.  Its uses are redirected
			// by load(), while loop-carried and handler phis remain real labels.
		if (!policy.optimized()
					|| block.phis().stream().anyMatch(phi -> !optimizationGuards.allowPhiElision(phi))) return false;
		}
		if (block.exceptionValue() != null) return false;
		if (isExceptionBoundaryGlue(block)) return false;
		if (hasEmittableStatements(block)) return false;
		IrBlock next = nextBlock(block);
		if (next == null) return false;
		if (hasPhiCopies(block, next)) return false;
		IrTerminator terminator = block.terminator();
		if (terminator == null) return true;
		return terminator.kind() == IrTerminatorKind.GOTO && gotoTarget(block, terminator.payload()) == next;
	}

	private boolean isExceptionBoundaryGlue(@NotNull IrBlock block) {
		for (IrExceptionRegion region : method.exceptionRegions()) {
			IrBlock boundary = blockByOffset.get(region.endOffset());
			if (boundary != null && boundary.successors().contains(block))
				return true;
		}
		return false;
	}

	private boolean hasEmittableStatements(@NotNull IrBlock block) {
		List<IrStmt> statements = block.statements();
		for (int i = 0; i < statements.size(); i++) {
			if (shouldSkipSeparateEmission(statements, i, block.terminator())) continue;
			IrStmt statement = statements.get(i);
			switch (statement) {
				case IrOp op -> {
					if (op.canonical() != op) continue;
					return true;
				}
				case IrEffect ignored -> {
					return true;
				}
				case IrTerminator ignored -> {
				}
			}
		}
		return false;
	}

	private boolean isNonThrowingGlueBlock(@NotNull IrBlock block) {
		if (block.exceptionValue() != null) return false;
		if (!block.exceptionalSuccessors().isEmpty()) return false;
		if (!block.statements().isEmpty()) return false;
		IrTerminator terminator = block.terminator();
		if (terminator == null) return true;
		return terminator.kind() == IrTerminatorKind.GOTO;
	}

	private boolean hasPhiCopies(@NotNull IrBlock source, @NotNull IrBlock target) {
		for (IrPhi phi : target.phis()) {
			if (shouldEmitPhiCopy(source, phi)) return true;
		}
		return false;
	}

	private void emitPhiCopies(@NotNull IrBlock source, @NotNull IrBlock target) {
		if (policy.optimized()) {
			emitPhiCopiesLegacy(source, target);
			return;
		}
		List<IrPhi> destinations = new ArrayList<>();
		List<IrValue> inputs = new ArrayList<>();
		for (IrPhi phi : target.phis()) {
			IrValue input = phi.operands().get(source);
			if (input == null || !shouldEmitPhiCopy(source, phi)) continue;
			if (!phi.hasLocal()) {
				report(ConversionDiagnostic.Kind.VERIFIER, currentOffset(),
						"Missing JVM local for phi " + phi.id());
				continue;
			}
			destinations.add(phi);
			inputs.add(input);
		}
		// Snapshot every source before writing any destination. This is a simple
		// parallel-move implementation and is correct for swaps, wide values, and
		// handler edges without relying on source-local ordering.
		List<Integer> temporaries = new ArrayList<>(inputs.size());
		for (int i = 0; i < inputs.size(); i++) {
			IrPhi destination = destinations.get(i);
			load(inputs.get(i), destination.type());
			int temporary = nextLocal;
			nextLocal += ConversionSupport.slotSize(destination.type());
			temporaries.add(temporary);
			mv.visitVarInsn(IrValueEmitter.storeOpcode(destination.type()), temporary);
		}
		for (int i = 0; i < destinations.size(); i++) {
			IrPhi destination = destinations.get(i);
			mv.visitVarInsn(IrValueEmitter.loadOpcode(destination.type()), temporaries.get(i));
			mv.visitVarInsn(IrValueEmitter.storeOpcode(destination.type()), destination.local());
		}
	}

	private void emitPhiCopiesLegacy(@NotNull IrBlock source, @NotNull IrBlock target) {
		for (IrPhi phi : target.phis()) {
			IrValue input = phi.operands().get(source);
			if (input == null || !shouldEmitPhiCopy(source, phi)) continue;
			load(input, phi.type());
			store(phi);
		}
	}

	private boolean shouldEmitPhiCopy(@NotNull IrBlock source, @NotNull IrPhi phi) {
		if (phi.canonical() != phi)
			return false;
		if (policy.guardedExpressions()
				&& optimizationGuards.allowPhiElision(phi)) return false;
		if (!isLive(phi))
			return false;
		IrValue input = phi.operands().get(source);
		if (input == null)
			return false;
		IrValue canonicalInput = input.canonical();
		IrValue initializedValue = initializedPhiValues.get(phi);
		if (initializedValue != null && sameConstant(canonicalInput, initializedValue))
			return false;
		if (canonicalInput == phi)
			return false;
		return !canonicalInput.hasLocal() || !phi.hasLocal() || canonicalInput.local() != phi.local();
	}

	private IrBlock gotoTarget(@NotNull IrBlock block, Object payload) {
		if (payload instanceof GotoInstruction gotoInstruction)
			return blockByOffset.get(gotoInstruction.jump().position());
		if (!block.successors().isEmpty())
			return block.successors().getFirst();
		return null;
	}

	private @NotNull Label protectedBoundaryLabel(@NotNull IrBlock block) {
		return protectedBoundaryLabels.computeIfAbsent(block, ignored -> new Label());
	}

	private void emitProtectedBoundary(@NotNull IrBlock block) {
		if (!block.exceptionalSuccessors().isEmpty())
			mv.visitLabel(protectedBoundaryLabel(block));
	}

	private void emitIf(@NotNull IrBlock block, @NotNull IrTerminator terminator,
	                    @NotNull BranchInstruction instruction, @NotNull List<IrValue> inputs) {
		IrValue left = inputs.get(0).canonical();
		IrValue right = inputs.get(1).canonical();
		IrBlock trueTarget = blockByOffset.get(instruction.label().position());
		IrBlock falseTarget = block.successors().stream().filter(successor -> successor != trueTarget).findFirst().orElse(null);
		if (trueTarget == null)
			throw new IllegalStateException("Malformed branch successors");
		if (fullyInlinedReturnBlocks.contains(trueTarget) || fullyInlinedReturnBlocks.contains(falseTarget)) {
			Label trueEdge = new Label();
			emitIfCondition(terminator, instruction.opcode(), left, right, trueEdge, false);
			emitEdgeGoto(block, falseTarget == null ? trueTarget : falseTarget);
			mv.visitLabel(trueEdge);
			emitEdgeGoto(block, trueTarget);
			return;
		}
		if (falseTarget == null || falseTarget == trueTarget) {
			IrBlock next = plannedFallthrough(block);
			emitProtectedBoundary(block);
			if (trueTarget == next) {
				emitPhiCopies(block, trueTarget);
				emitIfCondition(terminator, instruction.opcode(), left, right, branchTargetLabel(trueTarget), false);
				return;
			}
			Label takenEdge = new Label();
			emitIfCondition(terminator, instruction.opcode(), left, right, takenEdge, false);
			emitEdgeGoto(block, trueTarget);
			mv.visitLabel(takenEdge);
			emitEdgeGoto(block, trueTarget);
			return;
		}
		IrBlock next = plannedFallthrough(block);
		if (next == falseTarget && !hasPhiCopies(block, trueTarget)) {
			emitIfCondition(terminator, instruction.opcode(), left, right, branchTargetLabel(trueTarget), false);
			emitEdgeFallthrough(block, falseTarget);
			return;
		}
		if (next == trueTarget && !hasPhiCopies(block, falseTarget)) {
		emitIfCondition(terminator, instruction.opcode(), left, right, branchTargetLabel(falseTarget), true);
			emitEdgeFallthrough(block, trueTarget);
			return;
		}

		Label trueEdge = new Label();
		emitIfCondition(terminator, instruction.opcode(), left, right, trueEdge, false);
		emitEdgeGoto(block, falseTarget);
		mv.visitLabel(trueEdge);
		emitEdgeGoto(block, trueTarget);
	}

	private void emitIfZero(@NotNull IrBlock block, @NotNull IrTerminator terminator,
	                       @NotNull BranchZeroInstruction instruction, @NotNull IrValue input) {
		// Branch emission is layout-sensitive: prefer fall-through edges when the
		// next JVM label already represents one branch, but preserve explicit edge
		// labels whenever phi copies or protected-boundary bookkeeping requires them.
		IrValue value = input.canonical();
		IrBlock trueTarget = blockByOffset.get(instruction.label().position());
		IrBlock falseTarget = block.successors().stream().filter(successor -> successor != trueTarget).findFirst().orElse(null);
		if (trueTarget == null) throw new IllegalStateException("Malformed branch-zero successors");
		if (fullyInlinedReturnBlocks.contains(trueTarget) || fullyInlinedReturnBlocks.contains(falseTarget)) {
			Label trueEdge = new Label();
			emitIfZeroCondition(terminator, instruction.opcode(), value, trueEdge, false);
			emitEdgeGoto(block, falseTarget == null ? trueTarget : falseTarget);
			mv.visitLabel(trueEdge);
			emitEdgeGoto(block, trueTarget);
			return;
		}
		if (falseTarget != null && falseTarget != trueTarget && !hasPhiCopies(block, falseTarget)
				&& deferredNullThrowInsertions.containsKey(nextBlock(block))) {
			// The null target was deferred next to the body.  Invert this branch so
			// the non-null path falls through into the body and the relocated throw
			// follows it, matching the source-level: if (resource == null) throw
			emitIfZeroCondition(terminator, instruction.opcode(), value, branchTargetLabel(falseTarget), true);
			return;
		}
		if (falseTarget != null && falseTarget != trueTarget && isKnownNonNullResource(block, value)) {
			// The resource was already checked on every path reaching this block.
			// Suppress the now-impossible null edge, which otherwise decompiles as a
			// dead "if (input == null) return ..." after the read loop.
			emitEdge(block, falseTarget, true);
			return;
		}
		if (falseTarget == null || falseTarget == trueTarget) {
			IrBlock next = plannedFallthrough(block);
			emitProtectedBoundary(block);
			if (trueTarget == next) {
				emitPhiCopies(block, trueTarget);
				emitIfZeroCondition(terminator, instruction.opcode(), value, branchTargetLabel(trueTarget), false);
				return;
			}
			Label takenEdge = new Label();
			emitIfZeroCondition(terminator, instruction.opcode(), value, takenEdge, false);
			emitEdgeGoto(block, trueTarget);
			mv.visitLabel(takenEdge);
			emitEdgeGoto(block, trueTarget);
			return;
		}
		IrBlock next = plannedFallthrough(block);
		if (next == falseTarget && !hasPhiCopies(block, trueTarget)) {
			emitIfZeroCondition(terminator, instruction.opcode(), value, branchTargetLabel(trueTarget), false);
			emitEdgeFallthrough(block, falseTarget);
			return;
		}
		if (next == trueTarget && !hasPhiCopies(block, falseTarget)) {
		emitIfZeroCondition(terminator, instruction.opcode(), value, branchTargetLabel(falseTarget), true);
			emitEdgeFallthrough(block, trueTarget);
			return;
		}

		Label trueEdge = new Label();
		emitIfZeroCondition(terminator, instruction.opcode(), value, trueEdge, false);
		emitEdgeGoto(block, falseTarget);
		mv.visitLabel(trueEdge);
		emitEdgeGoto(block, trueTarget);
	}

	private boolean isKnownNonNullResource(@NotNull IrBlock block, @NotNull IrValue value) {
		// This is intentionally narrower than general nullness analysis.
		//
		// It only fires when a dominating null check branches to one of the deferred throw
		// blocks identified above.  The non-null successor must also dominate the
		// current block, and phi inputs are followed because loop/header joins can
		// carry the same InputStream under a different IR value.
		if (deferredNullThrowBlocks.isEmpty())
			return false;
		for (IrBlock candidate : method.blocks()) {
			IrTerminator terminator = candidate.terminator();
			if (terminator == null || terminator.kind() != IrTerminatorKind.IF_ZERO
					|| terminator.inputs().isEmpty() || !sameValueThroughPhi(terminator.inputs().getFirst(), value,
					new HashSet<>()) || !dominates(candidate, block)) continue;
			BranchZeroInstruction branch = (BranchZeroInstruction) terminator.payload();
			IrBlock zeroTarget = blockByOffset.get(branch.label().position());
			if (!deferredNullThrowBlocks.contains(zeroTarget)) continue;
			IrBlock nonNullTarget = candidate.successors().stream()
					.filter(successor -> successor != zeroTarget).findFirst().orElse(null);
			if (nonNullTarget != null && dominates(nonNullTarget, block)) return true;
		}
		return false;
	}

	private boolean sameValueThroughPhi(@NotNull IrValue first, @NotNull IrValue second,
	                                    @NotNull Set<IrValue> visited) {
		// Compare values through loop phis without treating a cyclic phi operand as evidence of a different value.
		IrValue expected = first.canonical();
		IrValue actual = second.canonical();
		if (expected == actual) return true;
		if (!(actual instanceof IrPhi phi) || !visited.add(phi))
			return false;
		for (IrValue operand : phi.operands().values()) {
			if (operand.canonical() == phi)
				continue;
			if (!sameValueThroughPhi(expected, operand, visited))
				return false;
		}
		return true;
	}

	private boolean dominates(@NotNull IrBlock dominator, @NotNull IrBlock block) {
		// A small predecessor-walk dominance check is sufficient here.  If every
		// path backwards from "block" reaches "dominator" before entry, the branch's
		// non-null fact is valid at the emission point.
		if (dominator == block) return true;
		IrBlock entry = method.blocks().isEmpty() ? null : method.blocks().getFirst();
		if (entry == null) return false;
		ArrayDeque<IrBlock> work = new ArrayDeque<>();
		Set<IrBlock> visited = new HashSet<>();
		work.add(block);
		while (!work.isEmpty()) {
			IrBlock current = work.removeFirst();
			if (!visited.add(current) || current == dominator) continue;
			if (current == entry) return false;
			work.addAll(current.predecessors());
		}
		return true;
	}

	private void emitIfCondition(@NotNull IrTerminator terminator, int opcode,
	                             @NotNull IrValue left, @NotNull IrValue right,
	                             @NotNull Label target, boolean inverted) {
		int effectiveOpcode = inverted ? IrControlFlowEmitter.invertIfOpcode(opcode) : opcode;
		if (ConversionSupport.isReferenceType(terminatorInputType(terminator, 0))
				&& ConversionSupport.isReferenceType(terminatorInputType(terminator, 1))) {
			load(left, terminatorInputType(terminator, 0));
			load(right, terminatorInputType(terminator, 1));
			mv.visitJumpInsn(switch (effectiveOpcode) {
				case Opcodes.IF_EQ -> IF_ACMPEQ;
				case Opcodes.IF_NE -> IF_ACMPNE;
				default ->
						throw new IllegalArgumentException("Unsupported reference branch opcode: " + effectiveOpcode);
			}, target);
			return;
		}
		load(left, terminatorInputType(terminator, 0));
		load(right, terminatorInputType(terminator, 1));
		mv.visitJumpInsn(switch (effectiveOpcode) {
			case Opcodes.IF_EQ -> IF_ICMPEQ;
			case Opcodes.IF_NE -> IF_ICMPNE;
			case Opcodes.IF_LT -> IF_ICMPLT;
			case Opcodes.IF_GE -> IF_ICMPGE;
			case Opcodes.IF_GT -> IF_ICMPGT;
			case Opcodes.IF_LE -> IF_ICMPLE;
			default -> throw new IllegalArgumentException("Unsupported branch opcode: " + effectiveOpcode);
		}, target);
	}

	private void emitIfZeroCondition(@NotNull IrTerminator terminator, int opcode,
	                                 @NotNull IrValue value, @NotNull Label target, boolean inverted) {
		int effectiveOpcode = inverted ? IrControlFlowEmitter.invertIfZeroOpcode(opcode) : opcode;
		if (ConversionSupport.isReferenceType(terminatorInputType(terminator, 0))) {
			load(value, terminatorInputType(terminator, 0));
			mv.visitJumpInsn(switch (effectiveOpcode) {
				case Opcodes.IF_EQZ -> IFNULL;
				case Opcodes.IF_NEZ -> IFNONNULL;
				default ->
						throw new IllegalArgumentException("Unsupported reference branch-zero opcode: " + effectiveOpcode);
			}, target);
			return;
		}
		load(value, terminatorInputType(terminator, 0));
		mv.visitJumpInsn(switch (effectiveOpcode) {
			case Opcodes.IF_EQZ -> IFEQ;
			case Opcodes.IF_NEZ -> IFNE;
			case Opcodes.IF_LTZ -> IFLT;
			case Opcodes.IF_GEZ -> IFGE;
			case Opcodes.IF_GTZ -> IFGT;
			case Opcodes.IF_LEZ -> IFLE;
			default -> throw new IllegalArgumentException("Unsupported branch-zero opcode: " + effectiveOpcode);
		}, target);
	}

	private void emitSwitch(@NotNull IrBlock block, @NotNull IrTerminator terminator) {
		IrValue input = terminator.inputs().getFirst().canonical();

		// Handle uninterrupted range switch --> tableswitch
		if (terminator.payload() instanceof PackedSwitchInstruction instruction) {
			List<Integer> labelOffsets = instruction.targets().stream()
					.map(me.darknet.dex.tree.definitions.instructions.Label::position)
					.toList();
			if (input.constantValue() instanceof Number number) {
				int index = number.intValue() - instruction.firstKey();
				IrBlock target = index >= 0 && index < instruction.targets().size()
						? blockByOffset.get(instruction.targets().get(index).position())
						: defaultSwitchTarget(block, labelOffsets);
				emitEdgeGoto(block, target);
				return;
			}
			load(input, terminatorInputType(terminator, 0));
			Label defaultEdge = new Label();
			Label[] labels = new Label[instruction.targets().size()];
			for (int i = 0; i < labels.length; i++)
				labels[i] = new Label();
			mv.visitTableSwitchInsn(instruction.firstKey(), instruction.firstKey() + labels.length - 1, defaultEdge, labels);
			for (int i = 0; i < labels.length; i++) {
				mv.visitLabel(labels[i]);
				emitEdgeGoto(block, blockByOffset.get(instruction.targets().get(i).position()));
			}
			mv.visitLabel(defaultEdge);
			emitEdgeGoto(block, defaultSwitchTarget(block, labelOffsets));
			return;
		}

		// Handle sparse switch --> lookupswitch
		SparseSwitchInstruction instruction = (SparseSwitchInstruction) terminator.payload();
		List<Map.Entry<Integer, me.darknet.dex.tree.definitions.instructions.Label>> entries = instruction.targets().entrySet().stream()
				.sorted(Map.Entry.comparingByKey()).toList();
		if (input.constantValue() instanceof Number number) {
			IrBlock target = instruction.targets().containsKey(number.intValue())
					? blockByOffset.get(instruction.targets().get(number.intValue()).position())
					: defaultSwitchTarget(block, entries.stream().map(entry -> entry.getValue().position()).toList());
			emitEdgeGoto(block, target);
			return;
		}
		load(input, terminatorInputType(terminator, 0));
		int[] keys = new int[entries.size()];
		Label[] labels = new Label[entries.size()];
		for (int i = 0; i < entries.size(); i++) {
			keys[i] = entries.get(i).getKey();
			labels[i] = new Label();
		}
		Label defaultEdge = new Label();
		mv.visitLookupSwitchInsn(defaultEdge, keys, labels);
		for (int i = 0; i < labels.length; i++) {
			mv.visitLabel(labels[i]);
			emitEdgeGoto(block, blockByOffset.get(entries.get(i).getValue().position()));
		}
		mv.visitLabel(defaultEdge);
		List<Integer> labelOffsets = entries.stream().map(entry -> entry.getValue().position()).toList();
		emitEdgeGoto(block, defaultSwitchTarget(block, labelOffsets));
	}

	private @NotNull IrBlock defaultSwitchTarget(@NotNull IrBlock block, @NotNull List<Integer> targetOffsets) {
		IrBlock next = nextBlock(block);
		if (next != null && block.successors().contains(next)) return next;
		return block.successors().stream()
				.filter(successor -> !targetOffsets.contains(successor.startOffset()))
				.findFirst()
				.orElseGet(() -> block.successors().stream()
						.findFirst()
						.orElseThrow(() -> new IllegalStateException("Malformed switch successors at offset "
								+ block.startOffset() + ", successors="
								+ block.successors().stream().map(IrBlock::startOffset).toList()
								+ ", targets=" + targetOffsets)));
	}

	private void emitReturn(@NotNull IrTerminator terminator, @NotNull ReturnInstruction instruction,
	                        @NotNull List<IrValue> inputs) {
		if (instruction.type() == me.darknet.dex.tree.definitions.instructions.Return.VOID) {
			mv.visitInsn(RETURN);
			return;
		}
		IrValue value = inputs.getFirst().canonical();
		// The JVM method descriptor is the final return contract.  The DEX
		// return opcode can be imprecise in malformed/partially recovered input;
		// loading against the method type routes that case through the typed
		// fallback instead of emitting a category-mismatched return.
		load(value, method.source().getType().returnType());
		emitReturnOpcode(instruction);
	}

	private void emitReturnOpcode(@NotNull ReturnInstruction instruction) {
		if (instruction.type() == me.darknet.dex.tree.definitions.instructions.Return.VOID) {
			mv.visitInsn(RETURN);
			return;
		}
		if (ConversionSupport.isReferenceType(method.source().getType().returnType())) {
			mv.visitInsn(ARETURN);
		} else if (ConversionSupport.isLongType(method.source().getType().returnType())) {
			mv.visitInsn(LRETURN);
		} else if (ConversionSupport.isDoubleType(method.source().getType().returnType())) {
			mv.visitInsn(DRETURN);
		} else if (ConversionSupport.isFloatType(method.source().getType().returnType())) {
			mv.visitInsn(FRETURN);
		} else {
			mv.visitInsn(IRETURN);
		}
	}

	private void emitOp(@NotNull IrOp op, @NotNull IrOperationEmitter.ResultMode resultMode) {
		if (!op.semantics().complete()) {
			report(ConversionDiagnostic.Kind.SEMANTICS, currentOffset(),
					"Incomplete semantic contract for operation " + op.semantics().constructionId()
							+ "; emitted a typed fallback");
			if (!ConversionSupport.isVoidType(op.type())) {
				emitTypedDefault(op.type());
				switch (resultMode) {
					case STORE -> store(op);
					case DISCARD -> IrValueEmitter.popValue(mv, op.type());
					case LEAVE_ON_STACK -> { }
				}
			}
			return;
		}
		operationEmitter.emit(op, resultMode);
	}

	private void emitEffect(@NotNull IrEffect effect) {
		if (!effect.semantics().complete()) {
			report(ConversionDiagnostic.Kind.SEMANTICS, currentOffset(),
					"Incomplete semantic contract for effect " + effect.semantics().constructionId()
					+ "; effect was conservatively suppressed");
			return;
		}
		IrEffectEmitter.emit(mv, effect, this::load, this::emitFillArrayData);
	}

	private void emitFillArrayData(@NotNull IrValue arrayValue, @NotNull ClassType expectedArrayType,
	                               @NotNull FillArrayDataInstruction instruction) {
		ClassType elementType = expectedArrayType instanceof ArrayType arrayType ? arrayType.componentType() : Types.INT;
		byte[] data = instruction.data();
		int width = instruction.elementSize();
		int elements = data.length / width;
		for (int i = 0; i < elements; i++) {
			load(arrayValue, expectedArrayType);
			ConversionSupport.pushInt(mv, i);
			IrValueEmitter.pushFilledArrayElement(mv, elementType, data, width, i);
			mv.visitInsn(ConversionSupport.arrayStoreOpcode(elementType));
		}
	}

	private void load(@NotNull IrValue value, @NotNull ClassType expectedType) {
		IrValue canonical = value.canonical();
		if (activeExpressionSlice != null && canonical instanceof IrOp operation
				&& activeExpressionSlice.operations().contains(operation)
				&& operation != activeExpressionOperation) {
			emitExpressionOperation(operation);
			return;
		}
		if (policy.guardedExpressions() && canonical instanceof IrPhi phi
				&& optimizationGuards.allowPhiElision(phi)) {
			IrValue replacement = uniformPhiValue(phi, new HashSet<>());
			if (replacement != null && replacement.canonical() != phi) {
				load(replacement, expectedType);
				return;
			}
		}
		if (canonical instanceof IrUnknown unknown) {
			emitUnknownDefault(unknown, expectedType);
			return;
		}
		if (!isJvmTypeCompatible(canonical.type(), expectedType)) {
			report(ConversionDiagnostic.Kind.TYPE_INFERENCE, currentOffset(),
					"Materialized a typed fallback for an incompatible JVM use: "
							+ canonical.type().descriptor() + " as " + expectedType.descriptor());
			emitTypedDefault(expectedType);
			return;
		}
		if (canonical instanceof IrConstant constant) {
			IrValueEmitter.pushConstant(mv, constant, expectedType);
			return;
		}
		if (policy.aggressiveCleanup() && canonical instanceof IrOp op) {
			JvmMaterializationDecision materialization = materializationPlan == null
					? null : materializationPlan.decision(canonical, currentStatement);
			if (materialization != null && materialization.inline()) {
				JvmSingleUsePlan singleUse = materialization.singleUsePlan();
				if (singleUse == null) throw new IllegalStateException("Inline materialization has no proof plan");
				if (singleUse.candidate().producer() == op
						|| singleUse.candidate().mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN
						&& singleUse.candidate().operations().getLast().canonical() == canonical) {
					emitSingleUseCandidate(singleUse.candidate());
					return;
				}
			}
		}
		if (policy.optimized()) {
			OperandStackState.Carry carry = activeOperandStackCarry(canonical);
			if (carry != null) {
				if (currentStatement != carry.consumer())
					throw new IllegalStateException("Operand-stack value consumed by an unexpected statement");
				operandStackState.remove(carry);
				expressionPlanner.invalidate();
				return;
			}
			if (canonical instanceof IrOp op && inlineConstructedReceivers.contains(op) && currentStatement != null
					&& (!policy.aggressiveCleanup()
					|| optimizationPlan.featureEnabled(JvmOptimizationFeature.RECEIVER_CHAINS))
					&& (!policy.aggressiveCleanup() || acceptedReceiverChainOperation(op))
					&& consumesConstructedReceiver(currentStatement, op)
					&& optimizationGuards.allowConstructedReceiver(op, currentStatement)) {
				emitConstructedReceiver(op);
				return;
			}
			if (canonical instanceof IrOp op
					&& !emittedOps.contains(op)
					&& canInlineIntoCurrentStatement(op)
					&& currentStatement != null
					&& optimizationGuards.allowInline(op, currentStatement)
					&& singleConsumerStatement(op) == currentStatement
					&& canInlineValue(op)) {
				IrStmt previousStatement = currentStatement;
				currentStatement = op;
				emitOp(op, IrOperationEmitter.ResultMode.LEAVE_ON_STACK);
				currentStatement = previousStatement;
				return;
			}
		}
		if (!canonical.hasLocal()) {
			report(ConversionDiagnostic.Kind.VERIFIER, currentOffset(),
					"Missing JVM local for nonconstant value " + canonical.id());
			emitTypedDefault(expectedType);
			return;
		}
		mv.visitVarInsn(IrValueEmitter.loadOpcode(expectedType), canonical.local());
	}

	private void emitSingleUseCandidate(@NotNull JvmSingleUseCandidate candidate) {
		if (candidate.mode() == JvmSingleUseCandidate.Mode.DEAD_CONSTRUCTION) return;
		/*
		 * A planned producer can itself consume another planned producer.  During
		 * that nested emission the semantic consumer must be the statement which
		 * owns the producer's result, rather than the outer statement currently
		 * being emitted.  Otherwise the inner materialization decision falls back
		 * to its local slot even though its producer was deliberately skipped.
		 */
		IrStmt previousStatement = currentStatement;
		if (candidate.consumer() != null) currentStatement = candidate.consumer();
		try {
			if (candidate.mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN) {
				emitReceiverChainCandidate(candidate);
				return;
			}
			if (candidate.mode() == JvmSingleUseCandidate.Mode.EXPRESSION_SLICE) {
				emitExpressionSliceCandidate(candidate);
				return;
			}
			IrOp producer = candidate.producer();
			if (candidate.mode() == JvmSingleUseCandidate.Mode.CONSTRUCTOR_CHAIN
					|| candidate.mode() == JvmSingleUseCandidate.Mode.CONSTRUCTOR_TO_THROW) {
				emitConstructedReceiver(producer);
			} else {
				// Inputs of this producer are consumed by the producer itself. This
				// matters when a direct candidate is nested inside another direct
				// candidate (for example, a substring used by StringBuilder.append).
				// Let their materialization decisions see the immediate consumer.
				currentStatement = producer;
				emitOp(producer, IrOperationEmitter.ResultMode.LEAVE_ON_STACK);
			}
			for (IrOp operation : candidate.operations()) emittedOps.add(operation);
		} finally {
			currentStatement = previousStatement;
		}
	}

	private void emitExpressionSliceCandidate(@NotNull JvmSingleUseCandidate candidate) {
		List<IrOp> operations = candidate.operations();
		if (operations.size() < 2)
			throw new IllegalStateException("Expression slice has fewer than two operations");
		JvmSingleUseCandidate previousSlice = activeExpressionSlice;
		IrOp previousOperation = activeExpressionOperation;
		activeExpressionSlice = candidate;
		try {
			emitExpressionOperation(operations.getLast());
			for (IrOp operation : operations) emittedOps.add(operation);
		} finally {
			activeExpressionSlice = previousSlice;
			activeExpressionOperation = previousOperation;
		}
	}

	private void emitExpressionOperation(@NotNull IrOp operation) {
		IrStmt previousStatement = currentStatement;
		IrOp previousOperation = activeExpressionOperation;
		activeExpressionOperation = operation;
		currentStatement = operation;
		try {
			emitOp(operation, IrOperationEmitter.ResultMode.LEAVE_ON_STACK);
		} finally {
			activeExpressionOperation = previousOperation;
			currentStatement = previousStatement;
		}
	}

	private void emitReceiverChainCandidate(@NotNull JvmSingleUseCandidate candidate) {
		List<IrOp> operations = candidate.operations();
		if (operations.size() < 2) throw new IllegalStateException("Receiver chain has fewer than two operations");
		IrStmt previousStatement = currentStatement;
		IrOp allocation = operations.getFirst();
		int index = 0;
		if (allocation.payload() instanceof NewInstanceInstruction newInstance) {
			if (operations.size() < 3 || !(operations.get(1).payload() instanceof InvokeInstruction constructor)
					|| !isConstructorInvoke(constructor))
				throw new IllegalStateException("Malformed constructor-backed receiver chain");
			currentStatement = allocation;
			mv.visitTypeInsn(NEW, newInstance.type().internalName());
			mv.visitInsn(DUP);
			IrOp constructorOp = operations.get(1);
			currentStatement = constructorOp;
			for (int input = 1; input < constructorOp.inputs().size(); input++)
				load(constructorOp.inputs().get(input), semanticInputType(constructorOp, input));
			mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(constructor.owner()),
					constructor.name(), constructor.type().descriptor(), false);
			emittedOps.add(allocation);
			emittedOps.add(constructorOp);
			index = 2;
		} else {
			currentStatement = allocation;
			emitOp(allocation, IrOperationEmitter.ResultMode.LEAVE_ON_STACK);
			emittedOps.add(allocation);
			index = 1;
		}
		for (; index < operations.size(); index++) {
			IrOp operation = operations.get(index);
			if (!(operation.payload() instanceof InvokeInstruction instruction)
					|| isConstructorInvoke(instruction) || operation.inputs().isEmpty()
					|| index + 1 < operations.size() && !isReceiverReturningInvoke(operation))
				throw new IllegalStateException("Malformed receiver-returning operation in chain");
			currentStatement = operation;
			for (int input = 1; input < operation.inputs().size(); input++)
				load(operation.inputs().get(input), semanticInputType(operation, input));
			mv.visitMethodInsn(ConversionSupport.invokeOpcode(instruction.opcode()),
					ConversionSupport.asmOwner(instruction.owner()), instruction.name(),
					instruction.type().descriptor(), instruction.opcode() == Invoke.INTERFACE);
			emittedOps.add(operation);
		}
		currentStatement = previousStatement;
	}

	private @NotNull ClassType semanticInputType(@NotNull IrOp op, int index) {
		if (index >= op.semantics().inputs().size())
			throw new IllegalStateException("Missing semantic input contract " + index + " for " + op);
		return refinedType(op.inputs().get(index), op.semantics().inputs().get(index).expected().materializedType());
	}

	private @NotNull ClassType terminatorInputType(@NotNull IrTerminator terminator, int index) {
		if (index >= terminator.semantics().inputs().size())
			throw new IllegalStateException("Missing semantic input contract " + index + " for " + terminator.kind());
		IrValue value = terminator.inputs().get(index).canonical();
		ClassType fallback = terminator.semantics().inputs().get(index).expected().materializedType();
		// IF_ZERO is shared by integer and reference null checks. Its conservative
		// descriptor fallback is integer-shaped, so prefer a proven reference flow
		// fact before materializing the JVM branch category. This is especially
		// important for synthetic resource-cleanup branches: emitting IFEQ for a
		// reference phi can make an otherwise reachable close() path dead.
		if (terminator.kind() == IrTerminatorKind.IF_ZERO) {
			IrType flowType = currentBlock == null ? null
					: method.flowFacts().getOrDefault(currentBlock, Map.of()).get(value);
			if (flowType != null && flowType.kind() == IrTypeKind.REFERENCE)
				return flowType.materializedType();
			IrType valueType = value.irType();
			if (valueType.kind() == IrTypeKind.REFERENCE)
				return valueType.materializedType();
			if (ConversionSupport.isReferenceType(value.type()))
				return value.type();
		}
		return refinedType(value, fallback);
	}

	private @NotNull ClassType refinedType(@NotNull IrValue value, @NotNull ClassType fallback) {
		if (currentBlock == null) return fallback;
		IrType fact = method.flowFacts().getOrDefault(currentBlock, Map.of()).get(value.canonical());
		if (fact == null || fact.kind() == me.darknet.dex.convert.ir.value.IrTypeKind.TOP
				|| fact.kind() == me.darknet.dex.convert.ir.value.IrTypeKind.UNKNOWN
				|| fact.kind() == me.darknet.dex.convert.ir.value.IrTypeKind.BOTTOM)
			return fallback;
		ClassType refined = fact.materializedType();
		return isJvmTypeCompatible(refined, fallback) ? refined : fallback;
	}

	private void emitUnknownDefault(@NotNull IrUnknown unknown, @NotNull ClassType expectedType) {
		if (reportedUnknowns.add(unknown))
			report(ConversionDiagnostic.Kind.FALLBACK, unknown.dexOffset(),
					"Materialized typed fallback for unknown JVM value " + unknown);
		ClassType type = isJvmTypeCompatible(unknown.type(), expectedType)
				? unknown.type() : expectedType;
		if (!unknown.type().equals(type))
			report(ConversionDiagnostic.Kind.TYPE_INFERENCE, unknown.dexOffset(),
					"Adjusted unknown JVM fallback from " + unknown.type().descriptor()
							+ " to its required use type " + expectedType.descriptor());
		emitTypedDefault(type);
	}

	private void coalesceProvenLongAccumulators() {
		if (!policy.guardedExpressions()) return;
		Map<IrValue, JvmLiveness.Interval> intervals = JvmLiveness.analyze(method);
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				if (!(statement instanceof IrOp op)
						|| !(op.payload() instanceof BinaryInstruction instruction)
						|| instruction.opcode() != Opcodes.ADD_LONG || op.inputs().size() != 2
						|| !(op.inputs().getFirst().canonical() instanceof IrPhi accumulator)
						|| !op.hasLocal() || !accumulator.hasLocal()
						|| op.local() == accumulator.local()
						|| !isProvenLongAccumulator(accumulator)
						|| !(op.inputs().get(1).canonical() instanceof IrOp conversion)
						|| !(conversion.payload() instanceof UnaryInstruction unary)
						|| unary.opcode() != Opcodes.INT_TO_LONG
						|| singleConsumerStatement(conversion) != op
						|| useCount(conversion) != 1)
					continue;
				JvmLiveness.Interval operationInterval = intervals.get(op);
				if (operationInterval == null) continue;
				boolean safe = true;
				for (Map.Entry<IrValue, JvmLiveness.Interval> entry : intervals.entrySet()) {
					IrValue other = entry.getKey().canonical();
					if (other == accumulator || other == op || !other.hasLocal()
							|| other.local() != accumulator.local()) continue;
					JvmLiveness.Interval otherInterval = entry.getValue();
					if (operationInterval.start() <= otherInterval.end()
							&& otherInterval.start() <= operationInterval.end()) {
						safe = false;
						break;
					}
				}
				if (safe) op.local(accumulator.local());
			}
		}
	}

	private void emitTypedDefault(@NotNull ClassType type) {
		if (ConversionSupport.isReferenceType(type)) {
			mv.visitInsn(ACONST_NULL);
		} else if (ConversionSupport.isLongType(type)) {
			ConversionSupport.pushLong(mv, 0L);
		} else if (ConversionSupport.isDoubleType(type)) {
			mv.visitInsn(DCONST_0);
		} else if (ConversionSupport.isFloatType(type)) {
			mv.visitInsn(FCONST_0);
		} else {
			ConversionSupport.pushInt(mv, 0);
		}
	}

	private boolean isJvmTypeCompatible(@NotNull ClassType actual, @NotNull ClassType expected) {
		if (ConversionSupport.isReferenceType(expected)) return ConversionSupport.isReferenceType(actual);
		if (ConversionSupport.isReferenceType(actual)) return false;
		if (ConversionSupport.isLongType(expected)) return ConversionSupport.isLongType(actual);
		if (ConversionSupport.isDoubleType(expected)) return ConversionSupport.isDoubleType(actual);
		if (ConversionSupport.isFloatType(expected)) return ConversionSupport.isFloatType(actual);
		return !ConversionSupport.isWideType(actual) && !ConversionSupport.isFloatType(actual);
	}

	private int currentOffset() {
		if (currentStatement instanceof IrOp op && op.payload() instanceof Instruction instruction) {
			Integer offset = method.source().getCode().offsetOf(instruction);
			if (offset != null) return offset;
		}
		if (currentStatement instanceof IrEffect effect && effect.payload() != null) {
			Integer offset = method.source().getCode().offsetOf(effect.payload());
			if (offset != null) return offset;
		}
		return -1;
	}

	private void report(@NotNull ConversionDiagnostic.Kind kind, int offset, @NotNull String message) {
		report(kind, offset, ConversionDiagnostic.Severity.WARNING, true, message);
	}

	private void report(@NotNull ConversionDiagnostic.Kind kind, int offset,
	                    @NotNull ConversionDiagnostic.Severity severity, boolean taints,
	                    @NotNull String message) {
		String className = method.source().getOwner() == null ? "<unknown>"
				: ConversionSupport.asmOwner(method.source().getOwner());
		loweringDiagnostics.add(new ConversionDiagnostic(className, method.source().toString(), offset,
				severity, kind, message, null));
		if (taints) tainted = true;
	}


	private boolean canInlineIntoCurrentStatement(@NotNull IrOp op) {
		if (op.stackOnly())
			return true;
		// Some DEX semantic classifiers conservatively attach an arithmetic
		// throw mask to the whole binary family. Allow only the non-throwing
		// arithmetic subset to fuse into a single consumer; division and
		// remainder continue through ordinary materialization.
		if (op.payload() instanceof BinaryInstruction instruction && nonThrowingArithmetic(instruction.opcode()))
			return true;
		if (op.payload() instanceof BinaryLiteralInstruction instruction && nonThrowingArithmetic(instruction.opcode()))
			return true;
		// Array length is a pure value-producing operation for JVM lowering.  It
		// may still throw for a null receiver, so the ordinary same-block,
		// single-consumer and exceptional-boundary guards remain authoritative.
		// Keeping it inline lets guarded mode recover the source-level
		// `array.length` loop bound without changing deterministic local mode.
		if (op.payload() instanceof ArrayLengthInstruction)
			return true;
		if (op.payload() instanceof FilledNewArrayInstruction)
			return true;
		// Array reads may throw, but a single-use read can still be fused into its
		// same-block consumer when allowInline() proves that evaluation and
		// exceptional boundaries are unchanged. Keep loop-body reads materialized
		// so the loop-shape proof can recover enhanced array iteration.
		if (op.payload() instanceof ArrayInstruction)
			return !isLoopBlock(blockContaining(op));
		if (op.payload() instanceof CheckCastInstruction)
			return true;
		if (op.payload() instanceof CompareInstruction)
			return true;
		if (op.semantics().throwMask() == 0)
			return true;
		if (op.payload() instanceof InstanceOfInstruction)
			return true;
		if (op.payload() instanceof InvokeInstruction)
			return true;
		return op.payload() instanceof StaticFieldInstruction || op.payload() instanceof InstanceFieldInstruction;
	}

	private static boolean nonThrowingArithmetic(int opcode) {
		return switch (opcode) {
			case Opcodes.ADD_INT, Opcodes.SUB_INT, Opcodes.MUL_INT,
					Opcodes.AND_INT, Opcodes.OR_INT, Opcodes.XOR_INT,
					Opcodes.SHL_INT, Opcodes.SHR_INT, Opcodes.USHR_INT,
					Opcodes.ADD_LONG, Opcodes.SUB_LONG, Opcodes.MUL_LONG,
					Opcodes.AND_LONG, Opcodes.OR_LONG, Opcodes.XOR_LONG,
					Opcodes.SHL_LONG, Opcodes.SHR_LONG, Opcodes.USHR_LONG,
					Opcodes.ADD_FLOAT, Opcodes.SUB_FLOAT, Opcodes.MUL_FLOAT,
					Opcodes.DIV_FLOAT, Opcodes.REM_FLOAT,
					Opcodes.ADD_DOUBLE, Opcodes.SUB_DOUBLE, Opcodes.MUL_DOUBLE,
					Opcodes.DIV_DOUBLE, Opcodes.REM_DOUBLE,
					Opcodes.ADD_INT_LIT8, Opcodes.ADD_INT_LIT16,
					Opcodes.RSUB_INT, Opcodes.RSUB_INT_LIT8,
					Opcodes.MUL_INT_LIT8, Opcodes.MUL_INT_LIT16,
					Opcodes.AND_INT_LIT8, Opcodes.AND_INT_LIT16,
					Opcodes.OR_INT_LIT8, Opcodes.OR_INT_LIT16,
					Opcodes.XOR_INT_LIT8, Opcodes.XOR_INT_LIT16,
					Opcodes.SHL_INT_LIT8, Opcodes.SHR_INT_LIT8, Opcodes.USHR_INT_LIT8 -> true;
			default -> false;
		};
	}

	private boolean canDeferEmissionToConsumer(@NotNull IrOp op) {
		return canInlineIntoCurrentStatement(op);
	}

	private void emitConstructedReceiver(@NotNull IrOp receiverOp) {
		IrOp constructorOp = constructorByReceiver.get(receiverOp);
		if (constructorOp == null || !(receiverOp.payload() instanceof NewInstanceInstruction newInstanceInstruction))
			throw new IllegalStateException("Missing constructor for inline receiver " + receiverOp.id());
		if (!(constructorOp.payload() instanceof InvokeInstruction constructorInstruction))
			throw new IllegalStateException("Inline receiver constructor payload is not an invoke: " + constructorOp.payload());
		IrStmt previousStatement = currentStatement;
		currentStatement = constructorOp;
		mv.visitTypeInsn(NEW, newInstanceInstruction.type().internalName());
		mv.visitInsn(DUP);
		for (int i = 1; i < constructorOp.inputs().size(); i++) {
			load(constructorOp.inputs().get(i), semanticInputType(constructorOp, i));
		}
		mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(constructorInstruction.owner()),
				constructorInstruction.name(), constructorInstruction.type().descriptor(), false);
		currentStatement = previousStatement;
	}

	private void store(@NotNull IrValue value) {
		if (!value.hasLocal()) {
			report(ConversionDiagnostic.Kind.VERIFIER, currentOffset(),
					"Missing JVM local for stored value " + value.id());
			IrValueEmitter.popValue(mv, value.type());
			return;
		}
		IrValueEmitter.store(mv, value);
	}

	private boolean tryEmitIncrement(@NotNull IrOp op, int constant,
	                                @NotNull IrOperationEmitter.ResultMode resultMode) {
		if (!policy.guardedExpressions() || resultMode != IrOperationEmitter.ResultMode.STORE
				|| !acceptedCountedLoopFor(op)
				|| constant < Short.MIN_VALUE || constant > Short.MAX_VALUE
				|| !(op.payload() instanceof BinaryLiteralInstruction)
				|| op.inputs().size() != 1 || !(op.inputs().getFirst().canonical() instanceof IrPhi source)
				|| !op.hasLocal() || !source.hasLocal() || op.local() != source.local()
				|| !optimizationGuards.safeOperation(op)) return false;
		mv.visitIincInsn(op.local(), constant);
		return true;
	}

	private boolean tryEmitLongIncrement(@NotNull IrOp op,
	                                    @NotNull IrOperationEmitter.ResultMode resultMode) {
		if (!canEmitLongIncrement(op, resultMode)) return false;
		IrValue accumulator = op.inputs().getFirst().canonical();
		IrOp conversion = (IrOp) op.inputs().get(1).canonical();
		IrValue count = conversion.inputs().getFirst();
		mv.visitVarInsn(LLOAD, accumulator.local());
		load(count, conversion.semantics().inputs().getFirst().expected().materializedType());
		mv.visitInsn(I2L);
		mv.visitInsn(LADD);
		mv.visitVarInsn(LSTORE, op.local());
		return true;
	}

	private boolean canEmitLongIncrement(@NotNull IrOp op,
	                                    @NotNull IrOperationEmitter.ResultMode resultMode) {
		if (!policy.guardedExpressions()
				|| resultMode != IrOperationEmitter.ResultMode.STORE
				|| !acceptedCountedLoopFor(op)
				|| !(op.payload() instanceof BinaryInstruction instruction)
				|| instruction.opcode() != Opcodes.ADD_LONG
				|| op.inputs().size() != 2
				|| !(op.inputs().getFirst().canonical() instanceof IrPhi accumulator)
				|| !(op.inputs().get(1).canonical() instanceof IrOp conversion)
				|| !(conversion.payload() instanceof UnaryInstruction conversionInstruction)
				|| conversionInstruction.opcode() != Opcodes.INT_TO_LONG
				|| conversion.inputs().size() != 1
				|| !op.hasLocal() || !accumulator.hasLocal() || op.local() != accumulator.local()
				|| !op.semantics().complete()
				|| !optimizationGuards.safeValue(op)
				|| !optimizationGuards.safeOperation(conversion)
				|| !isProvenLongAccumulator(accumulator)
				|| useCount(conversion) != 1 || singleConsumerStatement(conversion) != op)
			return false;
		IrBlock block = blockContaining(op);
		return block != null && block == blockContaining(conversion)
				&& isLoopBlock(block)
				&& block.exceptionValue() == null
				&& block.exceptionalSuccessors().isEmpty()
				&& op.irType().kind() == me.darknet.dex.convert.ir.value.IrTypeKind.LONG
				&& conversion.irType().kind() == me.darknet.dex.convert.ir.value.IrTypeKind.LONG
				&& conversion.inputs().getFirst().canonical().irType().kind()
					== me.darknet.dex.convert.ir.value.IrTypeKind.INT;
	}

	private boolean isProvenLongAccumulator(@NotNull IrValue value) {
		if (!(value.canonical() instanceof IrPhi phi) || phi.operands().isEmpty()) return false;
		if (phi.irType().kind() == me.darknet.dex.convert.ir.value.IrTypeKind.LONG)
			return optimizationGuards.safeValue(phi);
		// A loop phi can retain TOP from an earlier construction-time constraint
		// even after every finalized operand has converged to long.  This is still
		// a proof for the local-only update: every incoming value is a known long,
		// and the accumulator never crosses an exceptional edge on this block.
		if (phi.irType().kind() != me.darknet.dex.convert.ir.value.IrTypeKind.TOP
				|| !ConversionSupport.isLongType(phi.type())) return false;
		return phi.operands().values().stream().allMatch(input -> {
			IrValue canonical = input.canonical();
			return !(canonical instanceof IrUnknown) && !canonical.isImprecise()
					&& canonical.irType().kind() == me.darknet.dex.convert.ir.value.IrTypeKind.LONG;
		});
	}

	private boolean isLongIncrementConversion(@NotNull IrOp op) {
		if (!policy.guardedExpressions()
				|| !(op.payload() instanceof UnaryInstruction instruction)
				|| instruction.opcode() != Opcodes.INT_TO_LONG)
			return false;
		IrStmt consumer = singleConsumerStatement(op);
		return consumer instanceof IrOp consumerOp
				&& canEmitLongIncrement(consumerOp, IrOperationEmitter.ResultMode.STORE);
	}

}
