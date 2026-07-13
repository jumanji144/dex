package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.analysis.InstructionSemantics;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrParameter;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
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
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.SparseSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
	private final IrEmissionState emissionState;
	private final IrLoweringLayout layout;
	private final IrBlockEmitter blockEmitter = new IrBlockEmitter();
	private final IrSpecialChainEmitter specialChainEmitter = new IrSpecialChainEmitter();
	private final IrOperationEmitter operationEmitter;
	private final Map<IrBlock, Label> labels;
	private final Map<IrBlock, Label> protectedBoundaryLabels = new HashMap<>();
	private final Label endLabel;
	private final Map<HandlerStubKey, Label> handlerStubLabels = new HashMap<>();
	private final Map<IrBlock, Label> simpleHandlerStubLabels = new HashMap<>();
	private final Map<IrBlock, HandlerTail> handlerTails = new HashMap<>();
	private final Set<IrBlock> skippedHandlerTailBlocks = new HashSet<>();
	private final Set<IrBlock> deferredNullThrowBlocks = new HashSet<>();
	private final Map<IrBlock, List<IrBlock>> deferredNullThrowInsertions = new HashMap<>();
	private final Map<IrExceptionRegion, Label> tryStartLabels = new IdentityHashMap<>();
	private final Map<IrBlock, Label> tryStartLabelsByBlock = new HashMap<>();
	private final Set<IrBlock> deferredNormalTailBlocks = new HashSet<>();
	private final OperandStackState operandStackState = new OperandStackState();
	private final Map<IrPhi, IrValue> initializedPhiValues = new HashMap<>();
	private final Set<IrBlock> stubbedHandlers = new HashSet<>();
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
	private int nextLocal;
	private IrStmt currentStatement;

	private record HandlerStubKey(@NotNull IrBlock source, @NotNull IrBlock target) {}

	private record HandlerTail(@NotNull IrBlock root, @NotNull IrBlock target, @NotNull Label label) {}

	private record DirectReturn(@NotNull IrTerminator terminator, @NotNull IrValue value) {}

	private IrLoweringEngine(@NotNull IrMethod method, @NotNull MethodVisitor mv) {
		this.context = IrLoweringContext.create(method, mv);
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
		new IrLoweringEngine(method, mv).emit();
	}

	private void emit() {
		analyzeUses();
		expressionPlanner.reset(true);
		initializeLabels();
		nextLocal = JvmLocalAllocator.allocate(method, registerLocalBase);
		emittedOps.clear();
		emittedEffects.clear();
		collectHandlerStubs();
		collectHandlerTails();
		// A DEX try-with-resources lowering can leave the null-resource throw in a
		// separate nested region.  Plan its relocation before labels are emitted so
		// the JVM try range covers the same source statements as the Java try block.
		collectDeferredNullThrowBlocks();
		collectDeferredNormalTails();
		mv.visitCode();
		emitInitializedPhiValues();
		for (IrBlock block : method.blocks()) {
			// Some blocks are emitted at a different point in bytecode layout while retaining their original IR edges.
			// The deferred null-throw blocks are emitted immediately after the block that branches to them,
			// so the try range is not split before the null check.
			List<IrBlock> deferredNullThrow = deferredNullThrowInsertions.get(block);
			if (deferredNullThrow != null)
				for (IrBlock deferredBlock : deferredNullThrow)
					emitDeferredNullThrowBlock(deferredBlock);
			if (skippedHandlerTailBlocks.contains(block)
					|| deferredNormalTailBlocks.contains(block) || deferredNullThrowBlocks.contains(block)
					|| fullyInlinedReturnBlocks.contains(block))
				continue;
			if (isTransparentBlock(block))
				continue;
			mv.visitLabel(labels.get(block));
			if (block.exceptionValue() != null && !stubbedHandlers.contains(block))
				store(block.exceptionValue());
			blockEmitter.emitBody(block, blockEmitterHost());
		}
		emitDeferredNormalTails();
		mv.visitLabel(endLabel);
		emitHandlerTails();
		emitHandlerStubs();
		emitTryCatches();
		mv.visitMaxs(0xFF, nextLocal);
	}

	private @NotNull IrBlockEmitter.Host blockEmitterHost() {
		return new IrBlockEmitter.Host() {
			@Override
			public void beginOperandStackCarry(@NotNull IrBlock block) {
				IrLoweringEngine.this.beginOperandStackCarry(block);
			}

			@Override
			public boolean wasEffectEmitted(@NotNull IrEffect effect) {
				return emittedEffects.contains(effect);
			}

			@Override
			public boolean tryCarryInvokeInput(@NotNull IrBlock block, @NotNull List<IrStmt> statements,
			                                   int index, IrTerminator blockTerminator) {
				return IrLoweringEngine.this.tryCarryInvokeInput(block, statements, index, blockTerminator);
			}

			@Override
			public boolean isDirectPhiReturnOperand(@NotNull IrBlock block, @NotNull IrStmt statement) {
				return IrLoweringEngine.this.isDirectPhiReturnOperand(block, statement);
			}

			@Override
			public boolean shouldSkipSeparateEmission(@NotNull List<IrStmt> statements, int index,
			                                          IrTerminator blockTerminator) {
				return IrLoweringEngine.this.shouldSkipSeparateEmission(statements, index, blockTerminator);
			}

			@Override
			public int emitSpecialChain(@NotNull List<IrStmt> statements, int index) {
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
				return IrLoweringEngine.this.hasUnconsumedOperandStackCarry(block);
			}

			@Override
			public void clearOperandStackCarry() {
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
			public boolean shouldKeepConstructedInstance(@NotNull IrOp newInstanceOp) {
				return IrLoweringEngine.this.shouldKeepConstructedInstance(newInstanceOp);
			}

			@Override
			public boolean isOperationEmitted(@NotNull IrOp op) {
				return emittedOps.contains(op);
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
		deferredNullThrowInsertions.clear();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			boolean redundant = region.handlers().stream()
					.anyMatch(handler -> isRedundantNullResourceRegion(region, handler));
			if (!redundant) continue;
			IrExceptionRegion outer = method.exceptionRegions().stream()
					.filter(candidate -> candidate != region && candidate.endOffset() < region.startOffset()
							&& candidate.handlers().stream().anyMatch(handler -> region.handlers().stream()
							.anyMatch(inner -> handler.handlerBlock() == inner.handlerBlock())))
					.max(Comparator.comparingInt(IrExceptionRegion::endOffset))
					.orElse(null);
			if (outer == null) continue;
			List<IrBlock> blocks = new ArrayList<>(region.protectedBlocks());
			blocks.sort(Comparator.comparingInt(IrBlock::startOffset));
			IrBlock trySource = method.blocks().stream()
					.filter(candidate -> candidate.terminator() != null
							&& candidate.terminator().kind() == IrTerminatorKind.IF_ZERO
							&& candidate.terminator().payload() instanceof BranchZeroInstruction branch
							&& blockByOffset.get(branch.label().position()) == blocks.getFirst())
					.findFirst().orElse(null);
			if (trySource == null) continue;
			IrBlock insertion = nextBlock(trySource);
			if (insertion == null) continue;
			Label start = tryStartLabels.computeIfAbsent(outer, ignored -> new Label());
			tryStartLabelsByBlock.put(trySource, start);
			deferredNullThrowBlocks.addAll(blocks);
			deferredNullThrowInsertions.computeIfAbsent(insertion, ignored -> new ArrayList<>()).addAll(blocks);
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

	private boolean sameConstant(@NotNull IrValue first, @NotNull IrValue second) {
		return analysis.sameConstant(first, second);
	}

	private void collectHandlerStubs() {
		handlerStubLabels.clear();
		simpleHandlerStubLabels.clear();
		stubbedHandlers.clear();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			for (IrExceptionHandler handler : region.handlers()) {
				List<IrBlock> sources = coveredSourceBlocks(region, handler);
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
					HandlerStubKey key = new HandlerStubKey(source, handler.handlerBlock());
					handlerStubLabels.computeIfAbsent(key, ignored -> new Label());
				}
				stubbedHandlers.add(handler.handlerBlock());
			}
		}
	}

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
		for (IrBlock block : method.blocks()) {
			List<IrStmt> statements = block.statements();
			for (IrStmt statement : statements) {
				if (!(statement instanceof IrOp op) || op.canonical() != op) continue;
				IrOp receiver = constructedReceiver(op);
				if (receiver == null || useCount(receiver) != 2) continue;
				IrStmt consumer = constructedReceiverConsumer(receiver, op);
				if (consumer != null && consumesConstructedReceiver(consumer, receiver)
						&& canInlineConstructedReceiver(op, consumer)) {
					inlineConstructedReceivers.add(receiver);
				}
			}
		}
	}

	private void initializeLabels() {
		protectedBoundaryLabels.clear();
		layout.initializeLabels();
	}

	private void collectFullyInlinedReturnBlocks() {
		fullyInlinedReturnBlocks.clear();
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
		return expressionPlanner.shouldSkipSeparateEmission(statements, index, blockTerminator,
				this::computeShouldSkipSeparateEmission);
	}

	private boolean computeShouldSkipSeparateEmission(@NotNull List<IrStmt> statements, int index,
	                                                  @Nullable IrTerminator blockTerminator) {
		IrStmt statement = statements.get(index);
		if (!(statement instanceof IrOp op) || op.canonical() != op)
			return false;
		if (usesActiveOperandStackCarry(op))
			return false;
		IrStmt next = index + 1 < statements.size() ? statements.get(index + 1) : blockTerminator;
		if (shouldInlineConstructedReceiverConstructor(op))
			return true;
		if (shouldDeferConstructedReceiver(op))
			return true;
		if (shouldInlineConstructedReceiver(op, next))
			return true;
		if (isConstructorReceiverPair(op, next))
			return true;
		if (!canDeferEmissionToConsumer(op))
			return false;
		if (!canInlineValue(op))
			return false;
		IrStmt consumer = singleConsumerStatement(op);
		if (consumer == null)
			return false;
		int consumerIndex = consumer == blockTerminator ? statements.size() : statements.indexOf(consumer);
		if (consumerIndex <= index) {
			return consumerIndex < 0 && canDeferEmissionAcrossBlocks(op, statements, index, consumer, blockTerminator);
		}
		for (int i = index + 1; i < consumerIndex; i++)
			if (!shouldSkipSeparateEmission(statements, i, blockTerminator))
				return false;
		return true;
	}

	private boolean canDeferEmissionAcrossBlocks(@NotNull IrOp op, @NotNull List<IrStmt> statements, int index,
	                                             @NotNull IrStmt consumer, @Nullable IrTerminator blockTerminator) {
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
		if (consumerBlock != block && method.blocks().indexOf(consumerBlock) < method.blocks().indexOf(block))
			return false;
		if (usesActiveOperandStackCarry(op))
			return false;
		Set<IrBlock> carryRegion = null;
		if (consumerBlock == block) {
			if (consumerOp == null)
				return false;
			if (statements.indexOf(consumer) <= index || !canNestOperandStackCarry(block, op, consumer)
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
		return useCount(value) > 0 && !value.canonical().stackOnly();
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
				&& constructedReceiverConsumer(receiverOp, op) != null;
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
		if (consumerBlockIndex <= sourceIndex || sourceBlock.successors().size() != 1
				|| sourceBlock.successors().getFirst() != nextBlock(sourceBlock) || constructorIndex < 0)
			return false;
		for (int i = constructorIndex + 1; i < sourceBlock.statements().size(); i++)
			if (!shouldSkipSeparateEmission(sourceBlock.statements(), i, sourceBlock.terminator())) return false;
		for (int i = sourceIndex + 1; i < consumerBlockIndex; i++) {
			IrBlock block = method.blocks().get(i);
			if (!block.phis().isEmpty() || block.exceptionValue() != null || !block.exceptionalSuccessors().isEmpty()
					|| hasEmittableStatements(block) || block.successors().size() != 1
					|| block.successors().getFirst() != nextBlock(block)) return false;
		}
		return sourceBlock.exceptionalSuccessors().equals(consumerBlock.exceptionalSuccessors());
	}

	private @Nullable IrBlock blockContaining(@NotNull IrStmt statement) {
		for (IrBlock block : method.blocks()) {
			if (block.statements().contains(statement) || block.terminator() == statement) return block;
		}
		return null;
	}

	private static boolean isConstructorReceiverPair(@NotNull IrOp producer, IrStmt consumer) {
		if (!(producer.payload() instanceof NewInstanceInstruction))
			return false;
		if (!(consumer instanceof IrOp op))
			return false;
		if (!(op.payload() instanceof InvokeInstruction instruction) || !isConstructorInvoke(instruction))
			return false;
		return !op.inputs().isEmpty() && op.inputs().getFirst().canonical() == producer;
	}

	private boolean shouldDeferConstructedReceiver(@NotNull IrOp op) {
		return op.payload() instanceof NewInstanceInstruction && constructorByReceiver.containsKey(op);
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
		return receiver != null && inlineConstructedReceivers.contains(receiver) && consumesConstructedReceiver(next, receiver);
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
					load(invokeOp.inputs().get(inputIndex), IrOperationEmitter.invokeInputType(invokeInstruction, inputIndex));
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
					load(invokeOp.inputs().get(inputIndex), IrOperationEmitter.invokeInputType(invokeInstruction, inputIndex));
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
		if (!(statements.get(index) instanceof IrOp invokeOp)
				|| !(invokeOp.payload() instanceof InvokeInstruction invokeInstruction)
				|| !isConstructorInvoke(invokeInstruction) || invokeOp.inputs().isEmpty()) return false;
		IrValue receiver = invokeOp.inputs().getFirst().canonical();
		if (!(receiver instanceof IrOp receiverOp) || !(receiverOp.payload() instanceof NewInstanceInstruction newInstanceInstruction)
				|| useCount(receiverOp) != 2) return false;
		IrStmt consumer = constructedReceiverConsumer(receiverOp, invokeOp);
		if (!(consumer instanceof IrEffect effect) || blockContaining(invokeOp) == blockContaining(consumer)
				|| !canInlineConstructedReceiver(invokeOp, consumer)) return false;
		if (effect.payload() instanceof InstanceFieldInstruction fieldInstruction) {
			if (effect.inputs().size() < 2 || effect.inputs().get(1).canonical() != receiverOp) return false;
			IrStmt previousStatement = currentStatement;
			currentStatement = invokeOp;
			load(effect.inputs().getFirst(), fieldInstruction.owner());
			emitConstructAndPut(invokeOp, invokeInstruction, newInstanceInstruction, fieldInstruction);
			currentStatement = previousStatement;
		} else if (effect.payload() instanceof StaticFieldInstruction fieldInstruction) {
			if (effect.inputs().isEmpty() || effect.inputs().getFirst().canonical() != receiverOp) return false;
			IrStmt previousStatement = currentStatement;
			currentStatement = invokeOp;
			emitConstructAndPut(invokeOp, invokeInstruction, newInstanceInstruction, fieldInstruction);
			currentStatement = previousStatement;
		} else {
			return false;
		}
		emittedOps.add(invokeOp);
		emittedEffects.add(effect);
		return true;
	}

	private void emitConstructAndPut(@NotNull IrOp invokeOp, @NotNull InvokeInstruction invokeInstruction,
	                                 @NotNull NewInstanceInstruction newInstanceInstruction,
	                                 @NotNull InstanceFieldInstruction fieldInstruction) {
		mv.visitTypeInsn(NEW, newInstanceInstruction.type().internalName());
		mv.visitInsn(DUP);
		for (int inputIndex = 1; inputIndex < invokeOp.inputs().size(); inputIndex++)
			load(invokeOp.inputs().get(inputIndex), IrOperationEmitter.invokeInputType(invokeInstruction, inputIndex));
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
			load(invokeOp.inputs().get(inputIndex), IrOperationEmitter.invokeInputType(invokeInstruction, inputIndex));
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

		IrStmt previousStatement = currentStatement;
		currentStatement = arrayOp;
		load(arrayOp.inputs().getFirst(), Types.INT);
		ConversionSupport.emitNewArray(mv, newArrayInstruction.componentType());
		for (IrEffect effect : arrayStores) {
			currentStatement = effect;
			mv.visitInsn(DUP);
			load(effect.inputs().get(1), Types.INT);
			ClassType elementType = effect.inputs().get(2).type();
			load(effect.inputs().get(2), elementType);
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
			case IF -> emitIf(block, (BranchInstruction) terminator.payload(), terminator.inputs());
			case IF_ZERO ->
					emitIfZero(block, (BranchZeroInstruction) terminator.payload(), terminator.inputs().getFirst());
			case SWITCH -> emitSwitch(block, terminator);
			case RETURN -> emitReturn((ReturnInstruction) terminator.payload(), terminator.inputs());
			case THROW -> {
				load(terminator.inputs().getFirst(), terminator.inputs().getFirst().type());
				mv.visitInsn(ATHROW);
			}
		}
	}

	private void emitTryCatches() {
		for (IrExceptionRegion region : method.exceptionRegions()) {
			int effectiveTryCatchEnd = effectiveTryCatchEndOffset(region);
			for (IrExceptionHandler exceptionHandler : region.handlers()) {
				// The nested null-resource region is represented by the enclosing
				// catch-all after collectDeferredNullThrowBlocks() relocates its throw path.
				//
				// Registering it as a second JVM range would reintroduce the
				// split that produces incorrect out-of-scope catch variables.
				if (isSyntheticRethrowRegion(region, exceptionHandler)
						|| isRedundantNullResourceRegion(region, exceptionHandler)) continue;
				Handler handler = exceptionHandler.handler();
				String catchType = handler == null || handler.isCatchAll() ? null : handler.exceptionType().internalName();
				List<IrBlock> sources = coveredSourceBlocks(region, exceptionHandler);
				if (sources.isEmpty()) continue;
				boolean requiresHandlerStubs = simpleHandlerStubLabels.containsKey(exceptionHandler.handlerBlock())
						|| sources.stream()
						.anyMatch(source -> handlerStubLabels.containsKey(new HandlerStubKey(source, exceptionHandler.handlerBlock())));
				if (!requiresHandlerStubs) {
					Label start = tryStartLabels.getOrDefault(region, labelAtOrEnd(region.startOffset()));
					Label end = labelAtOrEnd(effectiveTryCatchEnd);
					if (start != end)
						mv.visitTryCatchBlock(start, end, handlerEntryLabel(exceptionHandler.handlerBlock()), catchType);
					continue;
				}
				Label rangeStart = null;
				Label rangeEnd = null;
				Label rangeHandler = null;
				for (IrBlock source : sources) {
					Label start = labels.get(source);
					Label end = protectedEndLabel(source, effectiveTryCatchEnd);
					if (start == end) continue;
					Label handlerLabel = handlerStubLabels.getOrDefault(new HandlerStubKey(source, exceptionHandler.handlerBlock()),
							handlerEntryLabel(exceptionHandler.handlerBlock()));
					if (rangeStart != null && rangeEnd == start && rangeHandler == handlerLabel) {
						rangeEnd = end;
						continue;
					}
					if (rangeStart != null)
						mv.visitTryCatchBlock(rangeStart, rangeEnd, rangeHandler, catchType);
					rangeStart = start;
					rangeEnd = end;
					rangeHandler = handlerLabel;
				}
				if (rangeStart != null) {
					mv.visitTryCatchBlock(rangeStart, rangeEnd, rangeHandler, catchType);
				}
			}
		}
	}

	private boolean isSyntheticRethrowRegion(@NotNull IrExceptionRegion region,
	                                         @NotNull IrExceptionHandler exceptionHandler) {
		return IrExceptionEmitter.isSyntheticRethrowRegion(method, region, exceptionHandler);
	}

	private boolean isRedundantNullResourceRegion(@NotNull IrExceptionRegion region,
	                                              @NotNull IrExceptionHandler exceptionHandler) {
		return IrExceptionEmitter.isRedundantNullResourceRegion(region, exceptionHandler, blockByOffset);
	}

	private void emitHandlerStubs() {
		// JVM handlers enter with the exception on the operand stack, while the IR
		// models it as a value in a handler block.  These labels bridge the two
		// representations and copy any handler-phi values before entering the real
		// handler body.  Without them, a split DEX try range can leave an invalid
		// stack/local shape for the verifier and decompiler.
		for (Map.Entry<IrBlock, Label> entry : simpleHandlerStubLabels.entrySet()) {
			IrBlock target = entry.getKey();
			mv.visitLabel(entry.getValue());
			if (target.exceptionValue() != null)
				store(target.exceptionValue());
			else
				mv.visitInsn(POP);
			mv.visitJumpInsn(GOTO, labels.get(target));
		}
		for (Map.Entry<HandlerStubKey, Label> entry : handlerStubLabels.entrySet()) {
			HandlerStubKey key = entry.getKey();
			IrBlock target = key.target();
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
				emitTailBlock(block);
			}
		}
	}

	private void emitDeferredNullThrowBlock(@NotNull IrBlock block) {
		// Emit the relocated null branch with its normal label and statements.  Its
		// original IR successors are preserved, so only bytecode layout changes.
		mv.visitLabel(labels.get(block));
		emitTailBlock(block);
	}

	private void emitTailBlock(@NotNull IrBlock block) {
		blockEmitter.emitBody(block, blockEmitterHost());
	}

	private @NotNull Label handlerEntryLabel(@NotNull IrBlock handler) {
		Label stub = simpleHandlerStubLabels.get(handler);
		if (stub != null) return stub;
		HandlerTail tail = handlerTails.get(handler);
		return tail == null ? labels.get(handler) : tail.label();
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
			sources.add(block);
		}
		return sources;
	}

	private void emitEdgeGoto(@NotNull IrBlock source, @NotNull IrBlock target) {
		if (tryEmitDirectPhiReturn(source, target)) return;
		emitPhiCopies(source, target);
		mv.visitJumpInsn(GOTO, labels.get(target));
	}

	private void emitEdgeFallthrough(@NotNull IrBlock source, @NotNull IrBlock target) {
		if (tryEmitDirectPhiReturn(source, target)) return;
		emitPhiCopies(source, target);
	}

	private boolean isDirectPhiReturnOperand(@NotNull IrBlock source, @NotNull IrStmt statement) {
		if (!ConversionSupport.isReferenceType(method.source().getType().returnType()))
			return false;
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
		if (canonicalOperand instanceof IrOp op && useCount(op) != 1) return false;
		IrStmt previousStatement = currentStatement;
		currentStatement = directReturn.terminator();
		if (canonicalOperand instanceof IrOp op)
			emitOp(op, IrOperationEmitter.ResultMode.LEAVE_ON_STACK);
		else
			load(canonicalOperand, method.source().getType().returnType());
		emitReturnOpcode((ReturnInstruction) directReturn.terminator().payload());
		currentStatement = previousStatement;
		return true;
	}

	private boolean canEmitDirectPhiReturn(@NotNull IrBlock source, @NotNull IrBlock target) {
		DirectReturn directReturn = directReturn(source, target);
		if (directReturn == null) return false;
		IrValue operand = directReturn.value().canonical();
		return !(operand instanceof IrOp op) || useCount(op) == 1;
	}

	private @Nullable DirectReturn directReturn(@NotNull IrBlock source, @NotNull IrBlock target) {
		String returnDescriptor = method.source().getType().returnType().descriptor();
		if (!ConversionSupport.isReferenceType(method.source().getType().returnType())
				&& !"Z".equals(returnDescriptor))
			return null;
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
				operand = operand == null ? null : uniformPhiValue(operand, new HashSet<>());
				return operand == null ? null : new DirectReturn(terminator, operand);
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
		int nextIndex = block.index() + 1;
		return nextIndex < method.blocks().size() ? method.blocks().get(nextIndex) : null;
	}

	private boolean isTransparentBlock(@NotNull IrBlock block) {
		if (!block.phis().isEmpty()) return false;
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
					if (op.canonical() != op || op.stackOnly()) continue;
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

	private void emitIf(@NotNull IrBlock block, @NotNull BranchInstruction instruction, @NotNull List<IrValue> inputs) {
		IrValue left = inputs.get(0).canonical();
		IrValue right = inputs.get(1).canonical();
		IrBlock trueTarget = blockByOffset.get(instruction.label().position());
		IrBlock falseTarget = block.successors().stream().filter(successor -> successor != trueTarget).findFirst().orElse(null);
		if (trueTarget == null)
			throw new IllegalStateException("Malformed branch successors");
		if (falseTarget == null || falseTarget == trueTarget) {
			IrBlock next = nextBlock(block);
			emitProtectedBoundary(block);
			if (trueTarget == next) {
				emitPhiCopies(block, trueTarget);
				emitIfCondition(instruction.opcode(), left, right, labels.get(trueTarget), false);
				return;
			}
			Label takenEdge = new Label();
			emitIfCondition(instruction.opcode(), left, right, takenEdge, false);
			emitEdgeGoto(block, trueTarget);
			mv.visitLabel(takenEdge);
			emitEdgeGoto(block, trueTarget);
			return;
		}
		if (fullyInlinedReturnBlocks.contains(trueTarget) || fullyInlinedReturnBlocks.contains(falseTarget)) {
			Label trueEdge = new Label();
			emitIfCondition(instruction.opcode(), left, right, trueEdge, false);
			emitEdgeGoto(block, falseTarget);
			mv.visitLabel(trueEdge);
			emitEdgeGoto(block, trueTarget);
			return;
		}

		IrBlock next = nextBlock(block);
		if (next == falseTarget && !hasPhiCopies(block, trueTarget)) {
			emitIfCondition(instruction.opcode(), left, right, labels.get(trueTarget), false);
			emitEdgeFallthrough(block, falseTarget);
			return;
		}
		if (next == trueTarget && !hasPhiCopies(block, falseTarget)) {
			emitIfCondition(instruction.opcode(), left, right, labels.get(falseTarget), true);
			emitEdgeFallthrough(block, trueTarget);
			return;
		}

		Label trueEdge = new Label();
		emitIfCondition(instruction.opcode(), left, right, trueEdge, false);
		emitEdgeGoto(block, falseTarget);
		mv.visitLabel(trueEdge);
		emitEdgeGoto(block, trueTarget);
	}

	private void emitIfZero(@NotNull IrBlock block, @NotNull BranchZeroInstruction instruction, @NotNull IrValue input) {
		// Branch emission is layout-sensitive: prefer fall-through edges when the
		// next JVM label already represents one branch, but preserve explicit edge
		// labels whenever phi copies or protected-boundary bookkeeping requires them.
		IrValue value = input.canonical();
		IrBlock trueTarget = blockByOffset.get(instruction.label().position());
		IrBlock falseTarget = block.successors().stream().filter(successor -> successor != trueTarget).findFirst().orElse(null);
		if (trueTarget == null) throw new IllegalStateException("Malformed branch-zero successors");
		if (falseTarget != null && falseTarget != trueTarget && !hasPhiCopies(block, falseTarget)
				&& deferredNullThrowInsertions.containsKey(nextBlock(block))) {
			// The null target was deferred next to the body.  Invert this branch so
			// the non-null path falls through into the body and the relocated throw
			// follows it, matching the source-level: if (resource == null) throw
			emitIfZeroCondition(instruction.opcode(), value, labels.get(falseTarget), true);
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
			IrBlock next = nextBlock(block);
			emitProtectedBoundary(block);
			if (trueTarget == next) {
				emitPhiCopies(block, trueTarget);
				emitIfZeroCondition(instruction.opcode(), value, labels.get(trueTarget), false);
				return;
			}
			Label takenEdge = new Label();
			emitIfZeroCondition(instruction.opcode(), value, takenEdge, false);
			emitEdgeGoto(block, trueTarget);
			mv.visitLabel(takenEdge);
			emitEdgeGoto(block, trueTarget);
			return;
		}
		if (fullyInlinedReturnBlocks.contains(trueTarget) || fullyInlinedReturnBlocks.contains(falseTarget)) {
			Label trueEdge = new Label();
			emitIfZeroCondition(instruction.opcode(), value, trueEdge, false);
			emitEdgeGoto(block, falseTarget);
			mv.visitLabel(trueEdge);
			emitEdgeGoto(block, trueTarget);
			return;
		}

		IrBlock next = nextBlock(block);
		if (next == falseTarget && !hasPhiCopies(block, trueTarget)) {
			emitIfZeroCondition(instruction.opcode(), value, labels.get(trueTarget), false);
			emitEdgeFallthrough(block, falseTarget);
			return;
		}
		if (next == trueTarget && !hasPhiCopies(block, falseTarget)) {
			emitIfZeroCondition(instruction.opcode(), value, labels.get(falseTarget), true);
			emitEdgeFallthrough(block, trueTarget);
			return;
		}

		Label trueEdge = new Label();
		emitIfZeroCondition(instruction.opcode(), value, trueEdge, false);
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

	private void emitIfCondition(int opcode, @NotNull IrValue left, @NotNull IrValue right, @NotNull Label target, boolean inverted) {
		int effectiveOpcode = inverted ? IrControlFlowEmitter.invertIfOpcode(opcode) : opcode;
		if (IrControlFlowEmitter.usesReferenceCompare(effectiveOpcode, left, right)) {
			load(left, left.type());
			load(right, right.type());
			mv.visitJumpInsn(switch (effectiveOpcode) {
				case Opcodes.IF_EQ -> IF_ACMPEQ;
				case Opcodes.IF_NE -> IF_ACMPNE;
				default ->
						throw new IllegalArgumentException("Unsupported reference branch opcode: " + effectiveOpcode);
			}, target);
			return;
		}
		load(left, Types.INT);
		load(right, Types.INT);
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

	private void emitIfZeroCondition(int opcode, @NotNull IrValue value, @NotNull Label target, boolean inverted) {
		int effectiveOpcode = inverted ? IrControlFlowEmitter.invertIfZeroOpcode(opcode) : opcode;
		if (ConversionSupport.isReferenceType(value.type()) || value.isZeroConstant()) {
			load(value, value.type());
			mv.visitJumpInsn(switch (effectiveOpcode) {
				case Opcodes.IF_EQZ -> IFNULL;
				case Opcodes.IF_NEZ -> IFNONNULL;
				default ->
						throw new IllegalArgumentException("Unsupported reference branch-zero opcode: " + effectiveOpcode);
			}, target);
			return;
		}
		load(value, Types.INT);
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
			load(input, Types.INT);
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
		load(input, Types.INT);
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

	private void emitReturn(@NotNull ReturnInstruction instruction, @NotNull List<IrValue> inputs) {
		if (instruction.type() == me.darknet.dex.tree.definitions.instructions.Return.VOID) {
			mv.visitInsn(RETURN);
			return;
		}
		IrValue value = inputs.getFirst().canonical();
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
		operationEmitter.emit(op, resultMode);
	}

	private void emitEffect(@NotNull IrEffect effect) {
		IrEffectEmitter.emit(mv, effect, this::load, this::emitFillArrayData);
	}

	private void emitFillArrayData(@NotNull IrValue arrayValue, @NotNull FillArrayDataInstruction instruction) {
		ClassType elementType = arrayValue.type() instanceof ArrayType arrayType ? arrayType.componentType() : Types.INT;
		byte[] data = instruction.data();
		int width = instruction.elementSize();
		int elements = data.length / width;
		for (int i = 0; i < elements; i++) {
			load(arrayValue, arrayValue.type());
			ConversionSupport.pushInt(mv, i);
			IrValueEmitter.pushFilledArrayElement(mv, elementType, data, width, i);
			mv.visitInsn(ConversionSupport.arrayStoreOpcode(elementType));
		}
	}

	private void load(@NotNull IrValue value, @NotNull ClassType expectedType) {
		IrValue canonical = value.canonical();
		if (canonical instanceof IrConstant constant) {
			IrValueEmitter.pushConstant(mv, constant, expectedType);
			return;
		}
		OperandStackState.Carry carry = activeOperandStackCarry(canonical);
		if (carry != null) {
			if (currentStatement != carry.consumer())
				throw new IllegalStateException("Operand-stack value consumed by an unexpected statement");
			operandStackState.remove(carry);
			expressionPlanner.invalidate();
			return;
		}
		if (canonical instanceof IrOp op && inlineConstructedReceivers.contains(op) && currentStatement != null
				&& consumesConstructedReceiver(currentStatement, op)) {
			emitConstructedReceiver(op);
			return;
		}
		if (canonical instanceof IrOp op
				&& !emittedOps.contains(op)
				&& canInlineIntoCurrentStatement(op)
				&& currentStatement != null
				&& singleConsumerStatement(op) == currentStatement
				&& canInlineValue(op)) {
			IrStmt previousStatement = currentStatement;
			currentStatement = op;
			emitOp(op, IrOperationEmitter.ResultMode.LEAVE_ON_STACK);
			currentStatement = previousStatement;
			return;
		}
		mv.visitVarInsn(IrValueEmitter.loadOpcode(expectedType), canonical.local());
	}

	private boolean canInlineIntoCurrentStatement(@NotNull IrOp op) {
		if (op.stackOnly())
			return true;
		if (op.payload() instanceof FilledNewArrayInstruction)
			return true;
		if (op.payload() instanceof CheckCastInstruction)
			return true;
		if (op.payload() instanceof CompareInstruction)
			return true;
		if (op.payload() instanceof BinaryInstruction instruction && !InstructionSemantics.canThrow(instruction))
			return true;
		if (op.payload() instanceof InstanceOfInstruction)
			return true;
		if (op.payload() instanceof InvokeInstruction)
			return true;
		return op.payload() instanceof StaticFieldInstruction || op.payload() instanceof InstanceFieldInstruction;
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
			load(constructorOp.inputs().get(i), IrOperationEmitter.invokeInputType(constructorInstruction, i));
		}
		mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(constructorInstruction.owner()),
				constructorInstruction.name(), constructorInstruction.type().descriptor(), false);
		currentStatement = previousStatement;
	}

	private void store(@NotNull IrValue value) {
		IrValueEmitter.store(mv, value);
	}

}



