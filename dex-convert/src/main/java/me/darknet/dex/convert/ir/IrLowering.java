package me.darknet.dex.convert.ir;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrExceptionValue;
import me.darknet.dex.convert.ir.value.IrParameter;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
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
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.MonitorInstruction;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.SparseSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.UnaryInstruction;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

public final class IrLowering {
	private final IrMethod method;
	private final MethodVisitor mv;
	private final Map<IrBlock, Label> labels = new HashMap<>();
	private final Map<IrBlock, Label> protectedBoundaryLabels = new HashMap<>();
	private final Label endLabel = new Label();
	private final Map<HandlerStubKey, Label> handlerStubLabels = new HashMap<>();
	private final Set<IrBlock> stubbedHandlers = new HashSet<>();
	private final Map<Integer, IrBlock> blockByOffset = new HashMap<>();
	private final Set<IrValue> liveValues = new HashSet<>();
	private final Set<IrOp> emittedOps = new HashSet<>();
	private final Map<IrValue, Integer> useCounts = new HashMap<>();
	private final Map<IrValue, Object> singleConsumers = new HashMap<>();
	private final Map<IrOp, IrOp> constructorByReceiver = new HashMap<>();
	private final Set<IrOp> inlineConstructedReceivers = new HashSet<>();
	private final int registerLocalBase;
	private int nextLocal;
	private IrStmt currentStatement;
	private static final Object NON_STATEMENT_CONSUMER = new Object();
	private static final Object MULTIPLE_CONSUMERS = new Object();
	private static final Set<String> INLINE_FLUENT_BUILDERS = Set.of(
			"java/lang/StringBuilder",
			"java/lang/StringBuffer"
	);

	private enum ResultMode {
		STORE,
		DISCARD,
		LEAVE_ON_STACK
	}

	private record HandlerStubKey(@NotNull IrBlock source, @NotNull IrBlock target) {
	}

	private IrLowering(@NotNull IrMethod method, @NotNull MethodVisitor mv) {
		this.method = method;
		this.mv = mv;
		this.registerLocalBase = parameterSlots(method.source());
		for (IrBlock block : method.blocks())
			blockByOffset.put(block.startOffset(), block);
	}

	public static void emit(@NotNull IrMethod method, @NotNull MethodVisitor mv) {
		new IrLowering(method, mv).emit();
	}

	private void emit() {
		nextLocal = registerLocalBase + method.source().getCode().getRegisters();
		analyzeUses();
		initializeLabels();
		allocateLocals();
		emittedOps.clear();
		collectHandlerStubs();
		mv.visitCode();
		for (IrBlock block : method.blocks()) {
			if (isTransparentBlock(block))
				continue;
			mv.visitLabel(labels.get(block));
			if (block.exceptionValue() != null && !stubbedHandlers.contains(block))
				store(block.exceptionValue());
			List<IrStmt> statements = block.statements();
			for (int i = 0; i < statements.size(); i++) {
				IrStmt statement = statements.get(i);
				if (shouldSkipSeparateEmission(statements, i))
					continue;
				int chainLength = emitSpecialChain(statements, i);
				if (chainLength > 0) {
					i += chainLength - 1;
					continue;
				}
				currentStatement = statement;
				emitStatement(statement);
				if (statement instanceof IrOp op && op.canonical() == op) {
					emittedOps.add(op);
				}
				currentStatement = null;
			}
			currentStatement = block.terminator();
			emitTerminator(block);
			currentStatement = null;
		}
		mv.visitLabel(endLabel);
		emitHandlerStubs();
		emitTryCatches();
		mv.visitMaxs(0xFF, nextLocal);
	}

	private void collectHandlerStubs() {
		handlerStubLabels.clear();
		stubbedHandlers.clear();
		for (IrTryCatch tryCatch : method.tryCatches()) {
			List<IrBlock> sources = coveredSourceBlocks(tryCatch);
			boolean needsStub = sources.stream().anyMatch(source -> hasPhiCopies(source, tryCatch.handlerBlock()));
			if (!needsStub) continue;
			for (IrBlock source : sources) {
				HandlerStubKey key = new HandlerStubKey(source, tryCatch.handlerBlock());
				handlerStubLabels.computeIfAbsent(key, ignored -> new Label());
			}
			stubbedHandlers.add(tryCatch.handlerBlock());
		}
	}

	private void analyzeUses() {
		useCounts.clear();
		singleConsumers.clear();
		liveValues.clear();
		constructorByReceiver.clear();
		inlineConstructedReceivers.clear();
		for (IrBlock block : method.blocks()) {
			List<IrStmt> statements = block.statements();
			for (int i = 0; i < statements.size(); i++) {
				if (!(statements.get(i) instanceof IrOp op)) continue;
				IrOp receiver = constructedReceiver(op);
				if (receiver == null) continue;
				constructorByReceiver.put(receiver, op);
				IrStmt next = i + 1 < statements.size() ? statements.get(i + 1) : null;
				if (useCount(receiver) == 0 && next == null) {
					// Usage counts are populated below; defer the inline eligibility check.
				}
			}
			for (IrPhi phi : block.phis()) {
				for (IrValue input : phi.operands().values()) {
					recordNonStatementUse(input);
				}
			}
			for (IrStmt statement : block.statements()) {
				switch (statement) {
					case IrOp op -> op.inputs().forEach(input -> recordStatementUse(input, statement));
					case IrEffect effect -> effect.inputs().forEach(input -> recordStatementUse(input, statement));
					case IrTerminator ignored -> {
					}
				}
			}
			if (block.terminator() != null) {
				block.terminator().inputs().forEach(input -> recordStatementUse(input, block.terminator()));
			}
		}
		analyzeLiveness();
		for (IrBlock block : method.blocks()) {
			List<IrStmt> statements = block.statements();
			for (int i = 0; i < statements.size(); i++) {
				if (!(statements.get(i) instanceof IrOp op)) continue;
				IrOp receiver = constructedReceiver(op);
				if (receiver == null || useCount(receiver) != 2) continue;
				IrStmt next = i + 1 < statements.size() ? statements.get(i + 1) : null;
				if (consumesConstructedReceiver(next, receiver)) {
					inlineConstructedReceivers.add(receiver);
				}
			}
		}
	}

	private void initializeLabels() {
		labels.clear();
		protectedBoundaryLabels.clear();
		for (int i = method.blocks().size() - 1; i >= 0; i--) {
			IrBlock block = method.blocks().get(i);
			labels.put(block, isTransparentBlock(block) ? transparentBlockLabel(block) : new Label());
		}
	}

	private void analyzeLiveness() {
		ArrayDeque<IrValue> worklist = new ArrayDeque<>();
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				switch (statement) {
					case IrOp op -> op.inputs().forEach(input -> markLive(input, worklist));
					case IrEffect effect -> effect.inputs().forEach(input -> markLive(input, worklist));
					case IrTerminator ignored -> {
					}
				}
			}
			if (block.terminator() != null) {
				block.terminator().inputs().forEach(input -> markLive(input, worklist));
			}
		}
		while (!worklist.isEmpty()) {
			IrValue value = worklist.removeFirst();
			if (value instanceof IrPhi phi) {
				for (IrValue operand : phi.operands().values()) {
					markLive(operand, worklist);
				}
			}
		}
	}

	private void markLive(@NotNull IrValue value, @NotNull ArrayDeque<IrValue> worklist) {
		IrValue canonical = value.canonical();
		if (liveValues.add(canonical)) {
			worklist.addLast(canonical);
		}
	}

	private int emitSpecialChain(@NotNull List<IrStmt> statements, int index) {
		if (tryEmitConstructAndPutChain(statements, index)) return 2;
		return tryEmitArrayStaticPutChain(statements, index);
	}

	private void recordStatementUse(@NotNull IrValue value, @NotNull IrStmt consumer) {
		IrValue canonical = value.canonical();
		useCounts.merge(canonical, 1, Integer::sum);
		Object existing = singleConsumers.get(canonical);
		if (existing == null) {
			singleConsumers.put(canonical, consumer);
		} else if (existing != consumer) {
			singleConsumers.put(canonical, MULTIPLE_CONSUMERS);
		}
	}

	private void recordNonStatementUse(@NotNull IrValue value) {
		IrValue canonical = value.canonical();
		useCounts.merge(canonical, 1, Integer::sum);
		singleConsumers.put(canonical, NON_STATEMENT_CONSUMER);
	}

	private void allocateLocals() {
		assignParameterLocals();
		for (IrBlock block : method.blocks()) {
			if (block.exceptionValue() != null) allocate(block.exceptionValue());
			for (IrPhi phi : block.phis()) if (phi.canonical() == phi) allocatePhi(phi);
			for (IrStmt statement : block.statements()) {
				if (statement instanceof IrOp op && op.canonical() == op
						&& !ConversionSupport.isVoidType(op.type()) && !op.stackOnly()) {
					allocate(op);
				}
			}
		}
		IrValue[] entry = method.entry().exitState();
		if (entry != null) {
			for (IrValue value : entry) {
				if (value instanceof IrParameter parameter) allocate(parameter);
			}
		}
	}

	private void allocate(@NotNull IrValue value) {
		if (value.hasLocal() || value instanceof IrConstant) return;
		Integer register = preferredRegister(value);
		if (register != null) {
			allocateRegisterValue(value, register);
			return;
		}
		value.local(nextLocal);
		nextLocal += ConversionSupport.slotSize(value.type());
	}

	private void allocatePhi(@NotNull IrPhi phi) {
		allocateRegisterValue(phi, phi.register());
	}

	private void allocateRegisterValue(@NotNull IrValue value, int register) {
		if (value.hasLocal()) return;
		value.local(registerLocal(register));
		nextLocal = Math.max(nextLocal, value.local() + ConversionSupport.slotSize(value.type()));
	}

	private int registerLocal(int register) {
		return registerLocalBase + register;
	}

	private @Nullable Integer preferredRegister(@NotNull IrValue value) {
		if (value instanceof IrOp op && op.hasRegister()) return op.register();
		if (value instanceof IrExceptionValue exceptionValue && exceptionValue.hasRegister()) return exceptionValue.register();
		return null;
	}

	private void assignParameterLocals() {
		Map<Integer, IrParameter> parameters = new HashMap<>();
		for (IrParameter parameter : collectParameters()) {
			parameters.put(parameter.register(), parameter);
		}
		int sourceLocal = 0;
		int dexRegister = method.source().getCode().getRegisters() - method.source().getCode().getIn();
		if ((method.source().getAccess() & org.objectweb.asm.Opcodes.ACC_STATIC) == 0) {
			IrParameter parameter = parameters.get(dexRegister++);
			if (parameter != null) {
				parameter.local(sourceLocal);
			}
			sourceLocal++;
		}
		for (ClassType parameterType : method.source().getType().parameterTypes()) {
			IrParameter parameter = parameters.get(dexRegister);
			if (parameter != null) {
				parameter.local(sourceLocal);
			}
			sourceLocal += ConversionSupport.slotSize(parameterType);
			dexRegister += ConversionSupport.slotSize(parameterType);
		}
	}

	private @NotNull List<IrParameter> collectParameters() {
		Map<Integer, IrParameter> parameters = new HashMap<>();
		for (IrBlock block : method.blocks()) {
			for (IrPhi phi : block.phis()) {
				phi.operands().values().forEach(value -> collectParameter(parameters, value));
			}
			for (IrStmt statement : block.statements()) {
				switch (statement) {
					case IrOp op -> op.inputs().forEach(value -> collectParameter(parameters, value));
					case IrEffect effect -> effect.inputs().forEach(value -> collectParameter(parameters, value));
					case IrTerminator ignored -> {
					}
				}
			}
			if (block.terminator() != null) {
				block.terminator().inputs().forEach(value -> collectParameter(parameters, value));
			}
		}
		return parameters.values().stream().sorted(java.util.Comparator.comparingInt(IrParameter::register)).toList();
	}

	private static void collectParameter(@NotNull Map<Integer, IrParameter> parameters, @NotNull IrValue value) {
		IrValue canonical = value.canonical();
		if (canonical instanceof IrParameter parameter) {
			parameters.put(parameter.register(), parameter);
		}
	}

	private boolean shouldSkipSeparateEmission(@NotNull List<IrStmt> statements, int index) {
		IrStmt statement = statements.get(index);
		if (!(statement instanceof IrOp op) || op.canonical() != op)
			return false;
		IrStmt next = index + 1 < statements.size() ? statements.get(index + 1) : null;
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
		int consumerIndex = statements.indexOf(consumer);
		if (consumerIndex <= index)
			return false;
		for (int i = index + 1; i < consumerIndex; i++)
			if (!shouldSkipSeparateEmission(statements, i))
				return false;
		return true;
	}

	private IrStmt singleConsumerStatement(@NotNull IrValue value) {
		Object consumer = singleConsumers.get(value.canonical());
		return consumer instanceof IrStmt statement ? statement : null;
	}

	private int useCount(@NotNull IrValue value) {
		return useCounts.getOrDefault(value.canonical(), 0);
	}

	private boolean isLive(@NotNull IrValue value) {
		return liveValues.contains(value.canonical());
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
		if (!(statement instanceof IrOp op))
			return false;
		if (!(op.payload() instanceof InvokeInstruction instruction) || isConstructorInvoke(instruction) || op.inputs().isEmpty())
			return false;
		return op.inputs().getFirst().canonical() == receiver;
	}

	private static boolean isInlineFluentBuilderType(@NotNull ClassType type) {
		if (!(type instanceof ReferenceType referenceType))
			return false;
		return INLINE_FLUENT_BUILDERS.contains(referenceType.internalName());
	}

	private static boolean isInlineFluentBuilderInvoke(@NotNull InvokeInstruction instruction) {
		return instruction.opcode() != Invoke.STATIC
				&& isInlineFluentBuilderType(instruction.owner())
				&& instruction.type().returnType().descriptor().equals(instruction.owner().descriptor());
	}

	private static boolean isInlineFluentBuilderInvoke(@NotNull IrOp op) {
		return op.payload() instanceof InvokeInstruction instruction && isInlineFluentBuilderInvoke(instruction);
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
					load(invokeOp.inputs().get(inputIndex), invokeInputType(invokeInstruction, inputIndex));
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
					load(invokeOp.inputs().get(inputIndex), invokeInputType(invokeInstruction, inputIndex));
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
		switch (statement) {
			case IrOp op -> {
				if (op.canonical() != op) return;
				if (op.stackOnly()) return;
				emitOp(op, shouldStoreResult(op) ? ResultMode.STORE : ResultMode.DISCARD);
			}
			case IrEffect effect -> emitEffect(effect);
			case IrTerminator ignored -> {
			}
		}
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
		for (IrTryCatch tryCatch : method.tryCatches()) {
			Handler handler = tryCatch.handler();
			String catchType = handler == null || handler.isCatchAll() ? null : handler.exceptionType().internalName();
			int effectiveTryCatchEnd = effectiveTryCatchEndOffset(tryCatch);
			Label rangeStart = null;
			Label rangeEnd = null;
			Label rangeHandler = null;
			for (IrBlock source : coveredSourceBlocks(tryCatch)) {
				Label start = labels.get(source);
				Label end = protectedEndLabel(source, effectiveTryCatchEnd);
				if (start == end) continue;
				Label handlerLabel = handlerStubLabels.getOrDefault(new HandlerStubKey(source, tryCatch.handlerBlock()),
						labels.get(tryCatch.handlerBlock()));
				if (rangeStart != null && rangeEnd == start && rangeHandler == handlerLabel) {
					rangeEnd = end;
					continue;
				}
				if (rangeStart != null) {
					mv.visitTryCatchBlock(rangeStart, rangeEnd, rangeHandler, catchType);
				}
				rangeStart = start;
				rangeEnd = end;
				rangeHandler = handlerLabel;
			}
			if (rangeStart != null) {
				mv.visitTryCatchBlock(rangeStart, rangeEnd, rangeHandler, catchType);
			}
		}
	}

	private void emitHandlerStubs() {
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
			mv.visitJumpInsn(GOTO, labels.get(target));
		}
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

	private int effectiveTryCatchEndOffset(@NotNull IrTryCatch tryCatch) {
		int endOffset = tryCatch.endOffset();
		IrBlock boundary = blockByOffset.get(endOffset);
		while (boundary != null && isNonThrowingGlueBlock(boundary)) {
			IrBlock next = nextBlock(boundary);
			endOffset = next == null ? Integer.MAX_VALUE : next.startOffset();
			boundary = next;
		}
		return endOffset;
	}

	private @NotNull List<IrBlock> coveredSourceBlocks(@NotNull IrTryCatch tryCatch) {
		List<IrBlock> sources = new ArrayList<>();
		for (IrBlock block : method.blocks()) {
			if (!block.exceptionalSuccessors().contains(tryCatch.handlerBlock())) continue;
			if (block.startOffset() >= tryCatch.endOffset()) continue;
			if (blockEndOffset(block) <= tryCatch.startOffset()) continue;
			sources.add(block);
		}
		return sources;
	}

	private void emitEdgeGoto(@NotNull IrBlock source, @NotNull IrBlock target) {
		emitPhiCopies(source, target);
		mv.visitJumpInsn(GOTO, labels.get(target));
	}

	private void emitEdgeFallthrough(@NotNull IrBlock source, @NotNull IrBlock target) {
		emitPhiCopies(source, target);
	}

	private void emitEdge(@NotNull IrBlock source, @NotNull IrBlock target, boolean allowFallthrough) {
		if (allowFallthrough && target == nextBlock(source)) {
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
		if (hasEmittableStatements(block)) return false;
		IrBlock next = nextBlock(block);
		if (next == null) return false;
		if (hasPhiCopies(block, next)) return false;
		IrTerminator terminator = block.terminator();
		if (terminator == null) return true;
		return terminator.kind() == IrTerminatorKind.GOTO && gotoTarget(block, terminator.payload()) == next;
	}

	private boolean hasEmittableStatements(@NotNull IrBlock block) {
		List<IrStmt> statements = block.statements();
		for (int i = 0; i < statements.size(); i++) {
			if (shouldSkipSeparateEmission(statements, i)) continue;
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

	private @NotNull Label transparentBlockLabel(@NotNull IrBlock block) {
		IrBlock next = nextBlock(block);
		if (next == null)
			return endLabel;
		Label nextLabel = labels.get(next);
		return nextLabel != null ? nextLabel : endLabel;
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
		IrValue value = input.canonical();
		IrBlock trueTarget = blockByOffset.get(instruction.label().position());
		IrBlock falseTarget = block.successors().stream().filter(successor -> successor != trueTarget).findFirst().orElse(null);
		if (trueTarget == null) throw new IllegalStateException("Malformed branch-zero successors");
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

	private void emitIfCondition(int opcode, @NotNull IrValue left, @NotNull IrValue right, @NotNull Label target, boolean inverted) {
		int effectiveOpcode = inverted ? invertIfOpcode(opcode) : opcode;
		if (usesReferenceCompare(effectiveOpcode, left, right)) {
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
		int effectiveOpcode = inverted ? invertIfZeroOpcode(opcode) : opcode;
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

	private void emitOp(@NotNull IrOp op, @NotNull ResultMode resultMode) {
		switch (op.payload()) {
			case BinaryInstruction instruction -> emitBinary(op, instruction, resultMode);
			case BinaryLiteralInstruction instruction -> emitBinaryLiteral(op, instruction, resultMode);
			case UnaryInstruction instruction -> emitUnary(op, instruction, resultMode);
			case CompareInstruction instruction -> emitCompare(op, instruction, resultMode);
			case me.darknet.dex.tree.definitions.instructions.ArrayLengthInstruction ignored -> {
				load(op.inputs().getFirst(), op.inputs().getFirst().type());
				mv.visitInsn(ARRAYLENGTH);
				finishValue(op, resultMode);
			}
			case CheckCastInstruction instruction -> {
				load(op.inputs().getFirst(), instruction.type());
				mv.visitTypeInsn(CHECKCAST, ConversionSupport.asmTypeOperand(instruction.type()));
				finishValue(op, resultMode);
			}
			case InstanceOfInstruction instruction -> {
				load(op.inputs().getFirst(), instruction.type());
				mv.visitTypeInsn(INSTANCEOF, ConversionSupport.asmTypeOperand(instruction.type()));
				finishValue(op, resultMode);
			}
			case NewInstanceInstruction instruction -> {
				mv.visitTypeInsn(NEW, instruction.type().internalName());
				finishValue(op, resultMode);
			}
			case NewArrayInstruction instruction -> {
				load(op.inputs().getFirst(), Types.INT);
				ConversionSupport.emitNewArray(mv, instruction.componentType());
				finishValue(op, resultMode);
			}
			case FilledNewArrayInstruction instruction -> emitFilledNewArray(op, instruction, resultMode);
			case ArrayInstruction instruction -> emitArrayGet(op, instruction, resultMode);
			case InstanceFieldInstruction instruction -> {
				load(op.inputs().getFirst(), instruction.owner());
				mv.visitFieldInsn(GETFIELD, instruction.owner().internalName(), instruction.name(), instruction.type().descriptor());
				finishValue(op, resultMode);
			}
			case StaticFieldInstruction instruction -> {
				mv.visitFieldInsn(GETSTATIC, instruction.owner().internalName(), instruction.name(), instruction.type().descriptor());
				finishValue(op, resultMode);
			}
			case InvokeInstruction instruction -> emitInvoke(op, instruction, resultMode);
			case InvokeCustomInstruction instruction -> emitInvokeCustom(op, instruction, resultMode);
			default -> throw new IllegalStateException("Unsupported op payload: " + op.payload());
		}
	}

	private void emitEffect(@NotNull IrEffect effect) {
		switch (effect.payload()) {
			case ArrayInstruction instruction -> {
				load(effect.inputs().get(0), effect.inputs().get(0).type());
				load(effect.inputs().get(1), Types.INT);
				ClassType elementType = effect.inputs().get(2).type();
				load(effect.inputs().get(2), elementType);
				mv.visitInsn(ConversionSupport.arrayStoreOpcode(elementType));
			}
			case InstanceFieldInstruction instruction -> {
				load(effect.inputs().get(0), instruction.owner());
				load(effect.inputs().get(1), instruction.type());
				mv.visitFieldInsn(PUTFIELD, instruction.owner().internalName(), instruction.name(), instruction.type().descriptor());
			}
			case StaticFieldInstruction instruction -> {
				load(effect.inputs().getFirst(), instruction.type());
				mv.visitFieldInsn(PUTSTATIC, instruction.owner().internalName(), instruction.name(), instruction.type().descriptor());
			}
			case FillArrayDataInstruction instruction -> emitFillArrayData(effect.inputs().get(0), instruction);
			case MonitorInstruction instruction -> {
				load(effect.inputs().getFirst(), effect.inputs().getFirst().type());
				mv.visitInsn(instruction.exit() ? MONITOREXIT : MONITORENTER);
			}
			default -> throw new IllegalStateException("Unsupported effect payload: " + effect.payload());
		}
	}

	private void emitBinary(@NotNull IrOp op, @NotNull BinaryInstruction instruction, @NotNull ResultMode resultMode) {
		load(op.inputs().get(0), operandTypeForBinary(instruction.opcode(), true));
		load(op.inputs().get(1), operandTypeForBinary(instruction.opcode(), false));
		mv.visitInsn(switch (instruction.opcode()) {
			case Opcodes.ADD_INT -> IADD;
			case Opcodes.SUB_INT -> ISUB;
			case Opcodes.MUL_INT -> IMUL;
			case Opcodes.DIV_INT -> IDIV;
			case Opcodes.REM_INT -> IREM;
			case Opcodes.AND_INT -> IAND;
			case Opcodes.OR_INT -> IOR;
			case Opcodes.XOR_INT -> IXOR;
			case Opcodes.SHL_INT -> ISHL;
			case Opcodes.SHR_INT -> ISHR;
			case Opcodes.USHR_INT -> IUSHR;
			case Opcodes.ADD_LONG -> LADD;
			case Opcodes.SUB_LONG -> LSUB;
			case Opcodes.MUL_LONG -> LMUL;
			case Opcodes.DIV_LONG -> LDIV;
			case Opcodes.REM_LONG -> LREM;
			case Opcodes.AND_LONG -> LAND;
			case Opcodes.OR_LONG -> LOR;
			case Opcodes.XOR_LONG -> LXOR;
			case Opcodes.SHL_LONG -> LSHL;
			case Opcodes.SHR_LONG -> LSHR;
			case Opcodes.USHR_LONG -> LUSHR;
			case Opcodes.ADD_FLOAT -> FADD;
			case Opcodes.SUB_FLOAT -> FSUB;
			case Opcodes.MUL_FLOAT -> FMUL;
			case Opcodes.DIV_FLOAT -> FDIV;
			case Opcodes.REM_FLOAT -> FREM;
			case Opcodes.ADD_DOUBLE -> DADD;
			case Opcodes.SUB_DOUBLE -> DSUB;
			case Opcodes.MUL_DOUBLE -> DMUL;
			case Opcodes.DIV_DOUBLE -> DDIV;
			case Opcodes.REM_DOUBLE -> DREM;
			default -> throw new IllegalArgumentException("Unsupported binary opcode: " + instruction.opcode());
		});
		finishValue(op, resultMode);
	}

	private void emitBinaryLiteral(@NotNull IrOp op, @NotNull BinaryLiteralInstruction instruction, @NotNull ResultMode resultMode) {
		load(op.inputs().getFirst(), Types.INT);
		ConversionSupport.pushInt(mv, instruction.constant());
		switch (instruction.opcode()) {
			case Opcodes.RSUB_INT, Opcodes.RSUB_INT_LIT8 -> {
				mv.visitInsn(SWAP);
				mv.visitInsn(ISUB);
			}
			case Opcodes.ADD_INT_LIT16, Opcodes.ADD_INT_LIT8 -> mv.visitInsn(IADD);
			case Opcodes.MUL_INT_LIT16, Opcodes.MUL_INT_LIT8 -> mv.visitInsn(IMUL);
			case Opcodes.DIV_INT_LIT16, Opcodes.DIV_INT_LIT8 -> mv.visitInsn(IDIV);
			case Opcodes.REM_INT_LIT16, Opcodes.REM_INT_LIT8 -> mv.visitInsn(IREM);
			case Opcodes.AND_INT_LIT16, Opcodes.AND_INT_LIT8 -> mv.visitInsn(IAND);
			case Opcodes.OR_INT_LIT16, Opcodes.OR_INT_LIT8 -> mv.visitInsn(IOR);
			case Opcodes.XOR_INT_LIT16, Opcodes.XOR_INT_LIT8 -> mv.visitInsn(IXOR);
			case Opcodes.SHL_INT_LIT8 -> mv.visitInsn(ISHL);
			case Opcodes.SHR_INT_LIT8 -> mv.visitInsn(ISHR);
			case Opcodes.USHR_INT_LIT8 -> mv.visitInsn(IUSHR);
			default -> throw new IllegalArgumentException("Unsupported binary literal opcode: " + instruction.opcode());
		}
		finishValue(op, resultMode);
	}

	private void emitUnary(@NotNull IrOp op, @NotNull UnaryInstruction instruction, @NotNull ResultMode resultMode) {
		load(op.inputs().getFirst(), op.inputs().getFirst().type());
		mv.visitInsn(switch (instruction.opcode()) {
			case Opcodes.NEG_INT -> INEG;
			case Opcodes.NEG_LONG -> LNEG;
			case Opcodes.NEG_FLOAT -> FNEG;
			case Opcodes.NEG_DOUBLE -> DNEG;
			case Opcodes.INT_TO_LONG -> I2L;
			case Opcodes.INT_TO_FLOAT -> I2F;
			case Opcodes.INT_TO_DOUBLE -> I2D;
			case Opcodes.LONG_TO_INT -> L2I;
			case Opcodes.LONG_TO_FLOAT -> L2F;
			case Opcodes.LONG_TO_DOUBLE -> L2D;
			case Opcodes.FLOAT_TO_INT -> F2I;
			case Opcodes.FLOAT_TO_LONG -> F2L;
			case Opcodes.FLOAT_TO_DOUBLE -> F2D;
			case Opcodes.DOUBLE_TO_INT -> D2I;
			case Opcodes.DOUBLE_TO_LONG -> D2L;
			case Opcodes.DOUBLE_TO_FLOAT -> D2F;
			case Opcodes.INT_TO_BYTE -> I2B;
			case Opcodes.INT_TO_CHAR -> I2C;
			case Opcodes.INT_TO_SHORT -> I2S;
			default -> Integer.MIN_VALUE;
		});
		if (instruction.opcode() == Opcodes.NOT_INT) {
			ConversionSupport.pushInt(mv, -1);
			mv.visitInsn(IXOR);
		} else if (instruction.opcode() == Opcodes.NOT_LONG) {
			ConversionSupport.pushLong(mv, -1L);
			mv.visitInsn(LXOR);
		}
		finishValue(op, resultMode);
	}

	private void emitCompare(@NotNull IrOp op, @NotNull CompareInstruction instruction, @NotNull ResultMode resultMode) {
		load(op.inputs().get(0), op.inputs().get(0).type());
		load(op.inputs().get(1), op.inputs().get(1).type());
		mv.visitInsn(switch (instruction.opcode()) {
			case Opcodes.CMPL_FLOAT -> FCMPL;
			case Opcodes.CMPG_FLOAT -> FCMPG;
			case Opcodes.CMPL_DOUBLE -> DCMPL;
			case Opcodes.CMPG_DOUBLE -> DCMPG;
			case Opcodes.CMP_LONG -> LCMP;
			default -> throw new IllegalArgumentException("Unsupported compare opcode: " + instruction.opcode());
		});
		finishValue(op, resultMode);
	}

	private void emitArrayGet(@NotNull IrOp op, @NotNull ArrayInstruction instruction, @NotNull ResultMode resultMode) {
		ClassType elementType = op.type();
		load(op.inputs().get(0), op.inputs().get(0).type());
		load(op.inputs().get(1), Types.INT);
		mv.visitInsn(ConversionSupport.arrayLoadOpcode(elementType));
		finishValue(op, resultMode);
	}

	private void emitFilledNewArray(@NotNull IrOp op, @NotNull FilledNewArrayInstruction instruction, @NotNull ResultMode resultMode) {
		ConversionSupport.pushInt(mv, op.inputs().size());
		ConversionSupport.emitNewArray(mv, instruction.componentType());
		ClassType elementType = ConversionSupport.arrayElementType(ConversionSupport.normalizeArrayType(instruction.componentType()));
		for (int i = 0; i < op.inputs().size(); i++) {
			mv.visitInsn(DUP);
			ConversionSupport.pushInt(mv, i);
			load(op.inputs().get(i), elementType);
			mv.visitInsn(ConversionSupport.arrayStoreOpcode(elementType));
		}
		finishValue(op, resultMode);
	}

	private void emitInvoke(@NotNull IrOp op, @NotNull InvokeInstruction instruction, @NotNull ResultMode resultMode) {
		if (isConstructorInvoke(instruction) && !op.inputs().isEmpty()) {
			IrValue receiver = op.inputs().getFirst().canonical();
			if (receiver instanceof IrOp receiverOp
					&& receiverOp.payload() instanceof NewInstanceInstruction newInstanceInstruction) {

				boolean keepConstructedInstance = shouldKeepConstructedInstance(receiverOp);
				if (emittedOps.contains(receiverOp)) {
					load(receiverOp, receiverOp.type());
				} else {
					mv.visitTypeInsn(NEW, newInstanceInstruction.type().internalName());
					if (keepConstructedInstance) {
						store(receiverOp);
						load(receiverOp, receiverOp.type());
					} else {
						mv.visitInsn(DUP);
					}
				}
				for (int i = 1; i < op.inputs().size(); i++) {
					load(op.inputs().get(i), invokeInputType(instruction, i));
				}
				mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(instruction.owner()),
						instruction.name(), instruction.type().descriptor(), false);
				if (!emittedOps.contains(receiverOp) && !keepConstructedInstance) {
					mv.visitInsn(POP);
				}
				return;
			}
		}
		for (int i = 0; i < op.inputs().size(); i++) {
			ClassType type = invokeInputType(instruction, i);
			load(op.inputs().get(i), type);
		}
		mv.visitMethodInsn(ConversionSupport.invokeOpcode(instruction.opcode()), ConversionSupport.asmOwner(instruction.owner()),
				instruction.name(), instruction.type().descriptor(), instruction.opcode() == Invoke.INTERFACE);
		if (!ConversionSupport.isVoidType(instruction.type().returnType())) finishValue(op, resultMode);
	}

	private void emitInvokeCustom(@NotNull IrOp op, @NotNull InvokeCustomInstruction instruction, @NotNull ResultMode resultMode) {
		for (int i = 0; i < op.inputs().size(); i++) {
			load(op.inputs().get(i), instruction.type().parameterTypes().get(i));
		}
		mv.visitInvokeDynamicInsn(instruction.name(), instruction.type().descriptor(),
				ConversionSupport.asmHandle(instruction.handle()), ConversionSupport.bootstrapArguments(instruction.arguments()));
		if (!ConversionSupport.isVoidType(instruction.type().returnType())) finishValue(op, resultMode);
	}

	private void emitFillArrayData(@NotNull IrValue arrayValue, @NotNull FillArrayDataInstruction instruction) {
		ClassType elementType = arrayValue.type() instanceof ArrayType arrayType ? arrayType.componentType() : Types.INT;
		byte[] data = instruction.data();
		int width = instruction.elementSize();
		int elements = data.length / width;
		for (int i = 0; i < elements; i++) {
			load(arrayValue, arrayValue.type());
			ConversionSupport.pushInt(mv, i);
			pushFilledArrayElement(elementType, data, width, i);
			mv.visitInsn(ConversionSupport.arrayStoreOpcode(elementType));
		}
	}

	private void finishValue(@NotNull IrValue value, @NotNull ResultMode resultMode) {
		switch (resultMode) {
			case STORE -> store(value);
			case DISCARD -> popValue(value.type());
			case LEAVE_ON_STACK -> {
			}
		}
	}

	private void popValue(@NotNull ClassType type) {
		mv.visitInsn(ConversionSupport.isWideType(type) ? POP2 : POP);
	}

	private void load(@NotNull IrValue value, @NotNull ClassType expectedType) {
		IrValue canonical = value.canonical();
		if (canonical instanceof IrConstant constant) {
			pushConstant(constant, expectedType);
			return;
		}
		if (canonical instanceof IrOp op && inlineConstructedReceivers.contains(op) && currentStatement != null
				&& consumesConstructedReceiver(currentStatement, op)) {
			emitConstructedReceiver(op);
			return;
		}
		if (canonical instanceof IrOp op
				&& canInlineIntoCurrentStatement(op)
				&& currentStatement != null
				&& singleConsumers.get(op.canonical()) == currentStatement
				&& canInlineValue(op)) {
			IrStmt previousStatement = currentStatement;
			currentStatement = op;
			emitOp(op, ResultMode.LEAVE_ON_STACK);
			currentStatement = previousStatement;
			return;
		}
		mv.visitVarInsn(loadOpcode(expectedType), canonical.local());
	}

	private boolean canInlineIntoCurrentStatement(@NotNull IrOp op) {
		if (op.stackOnly())
			return true;
		if (op.payload() instanceof FilledNewArrayInstruction)
			return true;
		if (isInlineFluentBuilderInvoke(op))
			return true;
		return op.payload() instanceof StaticFieldInstruction;
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
			load(constructorOp.inputs().get(i), invokeInputType(constructorInstruction, i));
		}
		mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(constructorInstruction.owner()),
				constructorInstruction.name(), constructorInstruction.type().descriptor(), false);
		currentStatement = previousStatement;
	}

	private void store(@NotNull IrValue value) {
		mv.visitVarInsn(storeOpcode(value.type()), value.local());
	}

	private void pushConstant(@NotNull IrConstant constant, @NotNull ClassType expectedType) {
		Object value = constant.constantValue();
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
			default -> {}
		}
		mv.visitLdcInsn(value);
	}

	private static int loadOpcode(@NotNull ClassType type) {
		if (ConversionSupport.isReferenceType(type)) return ALOAD;
		if (ConversionSupport.isLongType(type)) return LLOAD;
		if (ConversionSupport.isDoubleType(type)) return DLOAD;
		if (ConversionSupport.isFloatType(type)) return FLOAD;
		return ILOAD;
	}

	private static int storeOpcode(@NotNull ClassType type) {
		if (ConversionSupport.isReferenceType(type)) return ASTORE;
		if (ConversionSupport.isLongType(type)) return LSTORE;
		if (ConversionSupport.isDoubleType(type)) return DSTORE;
		if (ConversionSupport.isFloatType(type)) return FSTORE;
		return ISTORE;
	}

	private static int parameterSlots(@NotNull MethodMember method) {
		int slots = (method.getAccess() & org.objectweb.asm.Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (ClassType parameterType : method.getType().parameterTypes())
			slots += ConversionSupport.slotSize(parameterType);
		return slots;
	}

	private static boolean usesReferenceCompare(int opcode, @NotNull IrValue left, @NotNull IrValue right) {
		if (opcode != Opcodes.IF_EQ && opcode != Opcodes.IF_NE) return false;
		return ConversionSupport.isReferenceType(left.type())
				|| ConversionSupport.isReferenceType(right.type())
				|| (left.isZeroConstant() && ConversionSupport.isReferenceType(right.type()))
				|| (right.isZeroConstant() && ConversionSupport.isReferenceType(left.type()));
	}

	private static int invertIfOpcode(int opcode) {
		return switch (opcode) {
			case Opcodes.IF_EQ -> Opcodes.IF_NE;
			case Opcodes.IF_NE -> Opcodes.IF_EQ;
			case Opcodes.IF_LT -> Opcodes.IF_GE;
			case Opcodes.IF_GE -> Opcodes.IF_LT;
			case Opcodes.IF_GT -> Opcodes.IF_LE;
			case Opcodes.IF_LE -> Opcodes.IF_GT;
			default -> throw new IllegalArgumentException("Unsupported branch opcode: " + opcode);
		};
	}

	private static int invertIfZeroOpcode(int opcode) {
		return switch (opcode) {
			case Opcodes.IF_EQZ -> Opcodes.IF_NEZ;
			case Opcodes.IF_NEZ -> Opcodes.IF_EQZ;
			case Opcodes.IF_LTZ -> Opcodes.IF_GEZ;
			case Opcodes.IF_GEZ -> Opcodes.IF_LTZ;
			case Opcodes.IF_GTZ -> Opcodes.IF_LEZ;
			case Opcodes.IF_LEZ -> Opcodes.IF_GTZ;
			default -> throw new IllegalArgumentException("Unsupported branch-zero opcode: " + opcode);
		};
	}

	private static @NotNull ClassType invokeInputType(@NotNull InvokeInstruction instruction, int inputIndex) {
		if (instruction.opcode() != Invoke.STATIC) {
			if (inputIndex == 0) return instruction.owner();
			return instruction.type().parameterTypes().get(inputIndex - 1);
		}
		return instruction.type().parameterTypes().get(inputIndex);
	}

	private static @NotNull ClassType operandTypeForBinary(int opcode, boolean leftOperand) {
		if (opcode >= Opcodes.ADD_LONG && opcode <= Opcodes.USHR_LONG) {
			return switch (opcode) {
				case Opcodes.SHL_LONG, Opcodes.SHR_LONG, Opcodes.USHR_LONG -> leftOperand ? Types.LONG : Types.INT;
				default -> Types.LONG;
			};
		}
		if (opcode >= Opcodes.ADD_FLOAT && opcode <= Opcodes.REM_FLOAT) return Types.FLOAT;
		if (opcode >= Opcodes.ADD_DOUBLE && opcode <= Opcodes.REM_DOUBLE) return Types.DOUBLE;
		return Types.INT;
	}

	private void pushFilledArrayElement(@NotNull ClassType elementType, byte[] data, int width, int index) {
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
		return (data[offset] & 0xFF)
				| ((data[offset + 1] & 0xFF) << 8)
				| ((data[offset + 2] & 0xFF) << 16)
				| (data[offset + 3] << 24);
	}

	private static long readLong(byte[] data, int offset) {
		return (data[offset] & 0xFFL)
				| ((data[offset + 1] & 0xFFL) << 8)
				| ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24)
				| ((data[offset + 4] & 0xFFL) << 32)
				| ((data[offset + 5] & 0xFFL) << 40)
				| ((data[offset + 6] & 0xFFL) << 48)
				| ((data[offset + 7] & 0xFFL) << 56);
	}
}
