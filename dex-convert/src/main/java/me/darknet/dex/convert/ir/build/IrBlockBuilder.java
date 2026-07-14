package me.darknet.dex.convert.ir.build;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ConversionDiagnostic;
import me.darknet.dex.convert.ir.DexInstructionNode;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.DexIrException;
import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.analysis.IrInstructionSemantics;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrEffectKind;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrOpKind;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrExceptionValue;
import me.darknet.dex.convert.ir.value.IrParameter;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.ArrayLengthInstruction;
import me.darknet.dex.tree.definitions.instructions.Binary2AddrInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
import me.darknet.dex.tree.definitions.instructions.CompareInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstMethodHandleInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstMethodTypeInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstStringInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstTypeInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstWideInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.MonitorInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveObjectInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveResultInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveWideInstruction;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.NopInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.SparseSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import me.darknet.dex.tree.definitions.instructions.UnaryInstruction;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Handle;

import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import static me.darknet.dex.convert.ConversionSupport.slotSize;
import static me.darknet.dex.convert.ir.build.IrBuildingUtils.*;

public class IrBlockBuilder {
	private final MethodMember method;
	private final Code code;
	private final IrBuilder owner;
	private int nextValueId;
	private IrBlock activeBlock;
	private int activeOffset = -1;

	public IrBlockBuilder(@NotNull IrBuilder builder) {
		owner = builder;
		method = builder.getInputMethod();
		code = method.getCode();
	}

	public void buildBlocks(@NotNull IrGraph graph) {
		initializePhis(graph.blocks());
		initializeEntryState(graph.entry());
		for (IrBlock block : graph.blocks())
			buildBlock(block);
		// A join may be built before a later predecessor. Revisit all outgoing
		// edges once every block has an exit state so incomplete phis are sealed.
		for (IrBlock block : graph.blocks())
			if (block.exitState() != null) populatePhiInputs(block, block.exitState());
		removeTrivialPhis(graph.blocks());
	}

	private void initializePhis(@NotNull List<IrBlock> blocks) {
		// Phis are created lazily by read() when a value is needed at a join.
	}

	private void initializeEntryState(@NotNull IrBlock entry) {
		IrValue[] state = new IrValue[code.getRegisters()];
		int targetRegister = code.getRegisters() - code.getIn();
		if ((method.getAccess() & org.objectweb.asm.Opcodes.ACC_STATIC) == 0) {
			state[targetRegister] = new IrParameter(nextValueId++, method.getOwner(), targetRegister);
			targetRegister++;
		}
		for (ClassType parameterType : method.getType().parameterTypes()) {
			state[targetRegister] = new IrParameter(nextValueId++, parameterType, targetRegister);
			targetRegister += slotSize(parameterType);
		}
		entry.exitState(state.clone());
	}

	private void buildBlock(@NotNull IrBlock block) {
		IrValue[] state = new IrValue[code.getRegisters()];
		if (block.index() == 0 && block.exitState() != null) {
			System.arraycopy(block.exitState(), 0, state, 0, state.length);
		} else if (block.predecessors().size() == 1 && hasInputState(block.predecessors().getFirst(), block)) {
			System.arraycopy(requireInputState(block.predecessors().getFirst(), block), 0, state, 0, state.length);
		} else {
			boolean unsealed = block.predecessors().stream().anyMatch(predecessor -> !hasInputState(predecessor, block));
			// Empty join blocks still transfer the complete register state to their
			// successor. Without explicit merge values here, a later loop header sees
			// undefined registers even though every incoming edge defines them.
			if (unsealed || block.predecessors().size() > 1) {
				// A value read from a join without a copied predecessor state needs a
				// lazy merge value.  This also covers construction-only handler entries
				// and invoke payloads, whose input adapter cannot call read() itself.
				for (int register = 0; register < code.getRegisters(); register++) {
					if (state[register] == null)
						state[register] = ensurePhi(block, register, Types.INT);
				}
			}
		}

		activeBlock = block;
		IrValue pendingResult = initialPendingResult(block);
		for (DexInstructionNode node : block.dexInstructions()) {
			activeOffset = node.offset();
			Instruction instruction = node.instruction();
			IrInstructionSemantics constructionSemantics = IrInstructionSemantics.forConstruction(instruction);
			validateSemantics(constructionSemantics);
			if (!validateWideDestination(instruction)) continue;
			if (!(instruction instanceof MoveResultInstruction)) pendingResult = null;
			IrValue[] stateBeforeInstruction = null;
			if (!block.exceptionalSuccessors().isEmpty()
					&& IrInstructionSemantics.forThrowingInstruction(instruction).throwMask() != 0) {
				stateBeforeInstruction = state.clone();
			}
			int statementStart = block.statements().size();
			IrTerminator terminatorBefore = block.terminator();
			switch (instruction) {
				case ConstInstruction constInstruction ->
						state[constInstruction.register()] = constant(Types.INT, constInstruction.value(), constInstruction.value() == 0);
				case ConstMethodHandleInstruction constMethodHandleInstruction ->
						state[constMethodHandleInstruction.destination()] = constant(Types.instanceType(Handle.class), constMethodHandleInstruction.handle(), false);
				case ConstMethodTypeInstruction constMethodTypeInstruction ->
						state[constMethodTypeInstruction.destination()] = constant(Types.instanceType(MethodType.class), constMethodTypeInstruction.type(), false);
				case ConstStringInstruction constStringInstruction ->
						state[constStringInstruction.register()] = constant(Types.instanceType(String.class), constStringInstruction.string(), false);
				case ConstTypeInstruction constTypeInstruction ->
						state[constTypeInstruction.register()] = constant(Types.instanceType(Class.class), ConversionSupport.asmType(constTypeInstruction.type()), false);
				case ConstWideInstruction constWideInstruction ->
						state[constWideInstruction.register()] = constant(Types.LONG, constWideInstruction.value(), false);
				case MoveInstruction moveInstruction ->
						state[moveInstruction.to()] = read(state, moveInstruction.from());
				case MoveObjectInstruction moveObjectInstruction ->
						state[moveObjectInstruction.to()] = read(state, moveObjectInstruction.from());
				case MoveWideInstruction moveWideInstruction ->
						state[moveWideInstruction.to()] = read(state, moveWideInstruction.from());
				case MoveExceptionInstruction moveExceptionInstruction -> {
					IrExceptionValue value = block.ensureExceptionValue(nextValueId++, Types.instanceType(Throwable.class));
					value.register(moveExceptionInstruction.register());
					state[moveExceptionInstruction.register()] = value;
				}
				case BinaryInstruction binaryInstruction -> {
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.BINARY,
							binaryInstruction, 2);
					ClassType resultType = semantics.result().materializedType();
					IrOp op = new IrOp(nextValueId++, resultType, IrOpKind.BINARY,
							List.of(readTyped(state, binaryInstruction.a(), inputType(semantics, 0)),
									readTyped(state, binaryInstruction.b(), inputType(semantics, 1))),
							binaryInstruction, semantics);
					op.register(binaryInstruction.dest());
					block.statements().add(op);
					state[binaryInstruction.dest()] = op;
				}
				case Binary2AddrInstruction binary2AddrInstruction -> {
					BinaryInstruction normalized = normalize(binary2AddrInstruction);
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.BINARY,
							normalized, 2);
					ClassType resultType = semantics.result().materializedType();
					IrOp op = new IrOp(nextValueId++, resultType, IrOpKind.BINARY,
							List.of(readTyped(state, normalized.a(), inputType(semantics, 0)),
									readTyped(state, normalized.b(), inputType(semantics, 1))),
							normalized, semantics);
					op.register(binary2AddrInstruction.a());
					block.statements().add(op);
					state[binary2AddrInstruction.a()] = op;
				}
				case BinaryLiteralInstruction binaryLiteralInstruction -> {
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.BINARY_LITERAL,
							binaryLiteralInstruction, 1);
					IrOp op = new IrOp(nextValueId++, semantics.result().materializedType(), IrOpKind.BINARY_LITERAL,
							List.of(readTyped(state, binaryLiteralInstruction.src(), inputType(semantics, 0))),
							binaryLiteralInstruction, semantics);
					op.register(binaryLiteralInstruction.dest());
					block.statements().add(op);
					state[binaryLiteralInstruction.dest()] = op;
				}
				case UnaryInstruction unaryInstruction -> {
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.UNARY,
							unaryInstruction, 1);
					ClassType resultType = semantics.result().materializedType();
					IrOp op = new IrOp(nextValueId++, resultType, IrOpKind.UNARY,
							List.of(readTyped(state, unaryInstruction.source(), inputType(semantics, 0))),
							unaryInstruction, semantics);
					op.register(unaryInstruction.dest());
					block.statements().add(op);
					state[unaryInstruction.dest()] = op;
				}
				case CompareInstruction compareInstruction -> {
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.COMPARE,
							compareInstruction, 2);
					IrOp op = new IrOp(nextValueId++, Types.INT, IrOpKind.COMPARE,
							List.of(readTyped(state, compareInstruction.a(), inputType(semantics, 0)),
									readTyped(state, compareInstruction.b(), inputType(semantics, 1))),
							compareInstruction, semantics);
					op.register(compareInstruction.dest());
					block.statements().add(op);
					state[compareInstruction.dest()] = op;
				}
				case ArrayLengthInstruction arrayLengthInstruction -> {
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.ARRAY_LENGTH,
							arrayLengthInstruction, 1);
					IrOp op = new IrOp(nextValueId++, Types.INT, IrOpKind.ARRAY_LENGTH,
							List.of(readTyped(state, arrayLengthInstruction.array(), inputType(semantics, 0))),
							arrayLengthInstruction, semantics);
					op.register(arrayLengthInstruction.dest());
					block.statements().add(op);
					state[arrayLengthInstruction.dest()] = op;
				}
				case ArrayInstruction arrayInstruction -> buildArrayInstruction(block, state, arrayInstruction);
				case CheckCastInstruction checkCastInstruction -> {
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.CHECK_CAST,
							checkCastInstruction, 1);
					IrOp op = new IrOp(nextValueId++, checkCastInstruction.type(), IrOpKind.CHECK_CAST,
							List.of(readTyped(state, checkCastInstruction.register(), inputType(semantics, 0))),
							checkCastInstruction, semantics);
					op.register(checkCastInstruction.register());
					block.statements().add(op);
					state[checkCastInstruction.register()] = op;
				}
				case InstanceOfInstruction instanceOfInstruction -> {
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.INSTANCE_OF,
							instanceOfInstruction, 1);
					IrOp op = new IrOp(nextValueId++, Types.BOOLEAN, IrOpKind.INSTANCE_OF,
							List.of(readTyped(state, instanceOfInstruction.register(), inputType(semantics, 0))),
							instanceOfInstruction, semantics);
					op.register(instanceOfInstruction.destination());
					block.statements().add(op);
					state[instanceOfInstruction.destination()] = op;
				}
				case NewInstanceInstruction newInstanceInstruction -> {
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.NEW_INSTANCE,
							newInstanceInstruction, 0);
					IrOp op = new IrOp(nextValueId++, newInstanceInstruction.type(), IrOpKind.NEW_INSTANCE,
							List.of(), newInstanceInstruction, semantics);
					op.register(newInstanceInstruction.dest());
					block.statements().add(op);
					state[newInstanceInstruction.dest()] = op;
				}
				case NewArrayInstruction newArrayInstruction -> {
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.NEW_ARRAY,
							newArrayInstruction, 1);
					ClassType resultType = semantics.result().materializedType();
					IrOp op = new IrOp(nextValueId++, resultType, IrOpKind.NEW_ARRAY,
							List.of(readTyped(state, newArrayInstruction.sizeRegister(), inputType(semantics, 0))),
							newArrayInstruction, semantics);
					op.register(newArrayInstruction.dest());
					block.statements().add(op);
					state[newArrayInstruction.dest()] = op;
				}
				case FilledNewArrayInstruction filledNewArrayInstruction -> {
					ClassType resultType = ConversionSupport.normalizeArrayType(filledNewArrayInstruction.componentType());
					List<IrValue> inputs = loadFilledInputs(state, filledNewArrayInstruction, method, activeOffset);
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.FILLED_NEW_ARRAY,
							filledNewArrayInstruction, resultType, inputs.size());
					IrOp op = new IrOp(nextValueId++, resultType, IrOpKind.FILLED_NEW_ARRAY,
							inputs, filledNewArrayInstruction, semantics);
					block.statements().add(op);
					pendingResult = op;
				}
				case InvokeInstruction invokeInstruction -> {
					ClassType resultType = invokeInstruction.type().returnType();
					List<IrValue> inputs = loadInvokeInputs(state, invokeInstruction, method, activeOffset);
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.INVOKE,
							invokeInstruction, resultType, inputs.size());
					IrOp op = new IrOp(nextValueId++, resultType, IrOpKind.INVOKE,
							inputs, invokeInstruction, semantics);
					block.statements().add(op);
					pendingResult = op;
				}
				case InvokeCustomInstruction invokeCustomInstruction -> {
					ClassType resultType = invokeCustomInstruction.type().returnType();
					List<IrValue> inputs = loadInvokeInputs(state, invokeCustomInstruction, method, activeOffset);
					IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.INVOKE_CUSTOM,
							invokeCustomInstruction, resultType, inputs.size());
					IrOp op = new IrOp(nextValueId++, resultType, IrOpKind.INVOKE_CUSTOM,
							inputs, invokeCustomInstruction, semantics);
					block.statements().add(op);
					pendingResult = op;
				}
				case MoveResultInstruction moveResultInstruction -> {
					if (pendingResult == null) throw new DexIrException("lift", method, "move-result without producer");
					if (pendingResult instanceof IrOp op) {
						op.register(moveResultInstruction.to());
					}
					state[moveResultInstruction.to()] = pendingResult;
				}
				case InstanceFieldInstruction instanceFieldInstruction ->
						buildInstanceField(block, state, instanceFieldInstruction);
				case StaticFieldInstruction staticFieldInstruction ->
						buildStaticField(block, state, staticFieldInstruction);
				case FillArrayDataInstruction fillArrayDataInstruction ->
						block.statements().add(new IrEffect(IrEffectKind.FILL_ARRAY_DATA, List.of(read(state, fillArrayDataInstruction.array())), fillArrayDataInstruction));
				case MonitorInstruction monitorInstruction ->
						block.statements().add(new IrEffect(IrEffectKind.MONITOR, List.of(read(state, monitorInstruction.register())), monitorInstruction));
				case BranchInstruction branchInstruction ->
						block.terminator(new IrTerminator(IrTerminatorKind.IF, List.of(read(state, branchInstruction.a()), read(state, branchInstruction.b())), branchInstruction));
				case BranchZeroInstruction branchZeroInstruction ->
						block.terminator(new IrTerminator(IrTerminatorKind.IF_ZERO, List.of(read(state, branchZeroInstruction.a())), branchZeroInstruction));
				case GotoInstruction gotoInstruction ->
						block.terminator(new IrTerminator(IrTerminatorKind.GOTO, List.of(), gotoInstruction));
				case PackedSwitchInstruction packedSwitchInstruction ->
						block.terminator(new IrTerminator(IrTerminatorKind.SWITCH, List.of(read(state, packedSwitchInstruction.register())), packedSwitchInstruction));
				case SparseSwitchInstruction sparseSwitchInstruction ->
						block.terminator(new IrTerminator(IrTerminatorKind.SWITCH, List.of(read(state, sparseSwitchInstruction.register())), sparseSwitchInstruction));
				case ReturnInstruction returnInstruction -> block.terminator(new IrTerminator(IrTerminatorKind.RETURN,
						returnInstruction.type() == Return.VOID ? List.of() : List.of(read(state, returnInstruction.register())), returnInstruction));
				case ThrowInstruction throwInstruction -> {
					IrValue thrown = read(state, throwInstruction.value());
					block.terminator(new IrTerminator(IrTerminatorKind.THROW, List.of(thrown), throwInstruction));
				}
				case NopInstruction ignored -> {
				}
			default -> throw new DexIrException("lift", method, "Unsupported instruction: " + instruction);
			}
			for (int i = statementStart; i < block.statements().size(); i++) {
				IrStmt statement = block.statements().get(i);
				if (statement instanceof IrOp op) validateSemantics(op.semantics());
				if (statement instanceof IrEffect effect) validateSemantics(effect.semantics());
			}
			if (block.terminator() != null && block.terminator() != terminatorBefore)
				validateSemantics(block.terminator().semantics());
			if (stateBeforeInstruction != null) {
				for (IrExceptionEdge edge : block.exceptionEdges()) {
					if (edge.throwingInstruction() == instruction) {
						block.exceptionalExitStates().put(edge, stateBeforeInstruction.clone());
						if (block.exceptionalExitState() == null) block.exceptionalExitState(stateBeforeInstruction.clone());
					}
				}
			}
		}

		if (block.terminator() == null) {
			block.terminator(new IrTerminator(IrTerminatorKind.GOTO, List.of(), null));
		}
		block.exitState(state.clone());
		populatePhiInputs(block, state);
		activeBlock = null;
		activeOffset = -1;
	}

	private @NotNull IrValue[] requireState(@NotNull IrBlock block) {
		IrValue[] state = block.exitState();
		if (state == null)
			throw new DexIrException("lift", method, "Missing predecessor state for " + block.debugName());
		return state;
	}

	private @NotNull IrValue[] requireInputState(@NotNull IrBlock predecessor, @NotNull IrBlock block) {
		if (predecessor.exceptionalSuccessors().contains(block)) {
			for (IrExceptionEdge edge : predecessor.exceptionEdges()) {
				if (edge.handlerBlock() == block) {
					IrValue[] state = predecessor.exceptionalExitStates().get(edge);
					if (state != null) return state;
				}
			}
		}
		return requireState(predecessor);
	}

	private boolean hasInputState(@NotNull IrBlock predecessor, @NotNull IrBlock block) {
		if (predecessor.exceptionalSuccessors().contains(block)) {
			for (IrExceptionEdge edge : predecessor.exceptionEdges())
				if (edge.handlerBlock() == block && predecessor.exceptionalExitStates().containsKey(edge)) return true;
			return false;
		}
		return predecessor.exitState() != null;
	}

	private @Nullable IrValue initialPendingResult(@NotNull IrBlock block) {
		if (block.dexInstructions().isEmpty())
			return null;
		if (!(block.dexInstructions().getFirst().instruction() instanceof MoveResultInstruction))
			return null;
		if (block.index() == 0)
			return null;
		IrBlock predecessor = pendingResultProducerBlock(block);
		if (predecessor == null)
			return null;
		if (predecessor.statements().isEmpty())
			return null;
		IrStmt candidate = predecessor.statements().getLast();
		if (candidate instanceof IrOp op) {
			return switch (op.kind()) {
				case INVOKE, INVOKE_CUSTOM, FILLED_NEW_ARRAY -> op;
				default -> null;
			};
		}
		return null;
	}

	private @Nullable IrBlock pendingResultProducerBlock(@NotNull IrBlock block) {
		IrBlock current = block;
		while (true) {
			List<IrBlock> normalPredecessors = new ArrayList<>();
			for (IrBlock predecessor : current.predecessors()) {
				if (predecessor.successors().contains(current)) {
					normalPredecessors.add(predecessor);
				}
			}
			if (normalPredecessors.size() != 1) return null;
			IrBlock predecessor = normalPredecessors.getFirst();
			if (!predecessor.dexInstructions().isEmpty()) return predecessor;
			current = predecessor;
		}
	}

	private void buildArrayInstruction(@NotNull IrBlock block, @NotNull IrValue[] state, @NotNull ArrayInstruction instruction) {
		ClassType elementType = arrayElementType(instruction, state);
		if (instruction.opcode() < Opcodes.APUT) {
			IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.ARRAY_GET,
					instruction, elementType, 2);
			List<IrValue> inputs = List.of(readTyped(state, instruction.array(), inputType(semantics, 0)),
					readTyped(state, instruction.index(), inputType(semantics, 1)));
			semantics = IrInstructionSemantics.forOperation(IrOpKind.ARRAY_GET, instruction, elementType, inputs);
			IrOp op = new IrOp(nextValueId++, elementType, IrOpKind.ARRAY_GET, inputs, instruction, semantics);
			op.register(instruction.value());
			block.statements().add(op);
			state[instruction.value()] = op;
		} else {
			block.statements().add(new IrEffect(IrEffectKind.ARRAY_PUT,
					List.of(read(state, instruction.array()), readTyped(state, instruction.index(), Types.INT),
							readTyped(state, instruction.value(), elementType)),
					instruction));
		}
	}

	private void buildInstanceField(@NotNull IrBlock block, @NotNull IrValue[] state, @NotNull InstanceFieldInstruction instruction) {
		if (instruction.opcode() < Opcodes.IPUT) {
			IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.INSTANCE_GET,
					instruction, 1);
			IrOp op = new IrOp(nextValueId++, instruction.type(), IrOpKind.INSTANCE_GET,
					List.of(readTyped(state, instruction.instance(), inputType(semantics, 0))), instruction, semantics);
			op.register(instruction.value());
			block.statements().add(op);
			state[instruction.value()] = op;
		} else {
			block.statements().add(new IrEffect(IrEffectKind.INSTANCE_PUT,
					List.of(read(state, instruction.instance()), readTyped(state, instruction.value(), instruction.type())), instruction));
		}
	}

	private void buildStaticField(@NotNull IrBlock block, @NotNull IrValue[] state, @NotNull StaticFieldInstruction instruction) {
		if (instruction.opcode() < Opcodes.SPUT) {
			IrInstructionSemantics semantics = IrInstructionSemantics.forOperation(IrOpKind.STATIC_GET,
					instruction, 0);
			IrOp op = new IrOp(nextValueId++, instruction.type(), IrOpKind.STATIC_GET, List.of(), instruction, semantics);
			op.register(instruction.value());
			block.statements().add(op);
			state[instruction.value()] = op;
		} else {
			block.statements().add(new IrEffect(IrEffectKind.STATIC_PUT, List.of(read(state, instruction.value())), instruction));
		}
	}

	private static @NotNull ClassType inputType(@NotNull IrInstructionSemantics semantics, int index) {
		if (index < semantics.inputs().size()) return semantics.inputs().get(index).expected().materializedType();
		return Types.OBJECT;
	}

	private void validateSemantics(@NotNull IrInstructionSemantics semantics) {
		if (semantics.complete()) return;
		owner.report(new ConversionDiagnostic(
				method.getOwner() == null ? "<unknown>" : ConversionSupport.asmOwner(method.getOwner()),
				method.toString(), activeOffset, ConversionDiagnostic.Severity.WARNING,
				ConversionDiagnostic.Kind.SEMANTICS,
				"Incomplete semantic contract for " + semantics.constructionId(), null));
	}

	private void populatePhiInputs(@NotNull IrBlock block, @NotNull IrValue[] normalState) {
		for (IrBlock successor : block.successors()) {
			populatePhiInputs(block, successor, normalState);
		}
		for (IrBlock successor : block.exceptionalSuccessors()) {
			boolean populated = false;
			for (IrExceptionEdge edge : block.exceptionEdges()) {
				if (edge.handlerBlock() == successor) {
					IrValue[] exceptionalState = block.exceptionalExitStates().get(edge);
					if (exceptionalState != null) {
						populatePhiInputs(block, successor, exceptionalState);
						populated = true;
					}
				}
			}
			if (!populated) populatePhiInputs(block, successor, normalState);
		}
	}

	private void populatePhiInputs(@NotNull IrBlock predecessor, @NotNull IrBlock successor, @NotNull IrValue[] state) {
		if (successor.predecessors().size() <= 1) return;
		for (IrPhi phi : successor.phis()) {
			IrValue value = state[phi.register()];
			if (value == null)
				value = unknown(phi.type(), phi.register());
			phi.putOperand(predecessor, value);
			if (!value.isZeroConstant() && !value.type().equals(Types.INT))
				phi.constrain(me.darknet.dex.convert.ir.value.IrType.from(value));
		}
	}

	private @NotNull IrValue read(@NotNull IrValue[] state, int register) {
		if (register < 0 || register >= state.length) {
			return unknown(Types.INT, register);
		}
		IrValue value = state[register];
		if (value == null) {
			if (activeBlock != null && activeBlock.predecessors().size() > 1) {
				IrPhi phi = ensurePhi(activeBlock, register, Types.INT);
				state[register] = phi;
				return phi;
			}
			value = unknown(Types.OBJECT, register);
			state[register] = value;
		}
		return value;
	}

	private @NotNull IrValue readTyped(@NotNull IrValue[] state, int register, @NotNull ClassType expectedType) {
		IrValue value = read(state, register);
		if (value instanceof IrUnknown unknown) unknown.refine(expectedType);
		return adaptType(value, expectedType);
	}

	private @NotNull IrConstant constant(@NotNull ClassType type, @Nullable Object value, boolean zero) {
		return new IrConstant(nextValueId++, type, value, zero);
	}

	private @NotNull IrPhi ensurePhi(@NotNull IrBlock block, int register, @NotNull ClassType type) {
		for (IrPhi phi : block.phis()) if (phi.register() == register) return phi;
		IrPhi phi = new IrPhi(nextValueId++, block, register, type);
		block.phis().add(phi);
		return phi;
	}

	private @NotNull IrUnknown unknown(@NotNull ClassType expectedType, int register) {
		IrUnknown unknown = new IrUnknown(nextValueId++, expectedType, IrTypeKind.from(expectedType), method, activeOffset);
		return unknown;
	}

	private boolean validateWideDestination(@NotNull Instruction instruction) {
		int register = switch (instruction) {
			case ConstWideInstruction wide -> wide.register();
			case MoveWideInstruction wide -> wide.to();
			default -> -1;
		};
		if (register < 0) return true;
		if (register + 1 < code.getRegisters()) return true;
		owner.reportInvalid(ConversionDiagnostic.Kind.INVALID_WIDE_REGISTER, activeOffset,
				"Wide DEX value at register " + register + " has no valid register pair");
		return false;
	}

	void reportUnknowns(@NotNull List<IrBlock> blocks) {
		for (IrBlock block : blocks) {
			for (IrPhi phi : block.phis()) for (IrValue value : phi.operands().values()) reportUnknown(value, phi.register());
			for (IrStmt statement : block.statements()) {
				switch (statement) {
					case IrOp op -> op.inputs().forEach(value -> reportUnknown(value, -1));
					case IrEffect effect -> effect.inputs().forEach(value -> reportUnknown(value, -1));
					case IrTerminator terminator -> terminator.inputs().forEach(value -> reportUnknown(value, -1));
				}
			}
			if (block.terminator() != null) block.terminator().inputs().forEach(value -> reportUnknown(value, -1));
			if (block.exitState() != null) for (IrValue value : block.exitState()) reportUnknown(value, -1);
			for (IrValue[] state : block.exceptionalExitStates().values()) for (IrValue value : state) reportUnknown(value, -1);
		}
	}

	private void reportUnknown(@Nullable IrValue value, int register) {
		if (value instanceof IrUnknown unknown) owner.reportUnknown(unknown, register);
	}

	private void removeTrivialPhis(@NotNull List<IrBlock> blocks) {
		for (IrBlock block : blocks) {
			for (int index = block.phis().size() - 1; index >= 0; index--) {
				IrPhi phi = block.phis().get(index);
				IrValue replacement = null;
				boolean trivial = true;
				for (IrValue operand : phi.operands().values()) {
					IrValue canonical = operand.canonical();
					if (canonical == phi) continue;
					if (replacement == null) replacement = canonical;
					else if (replacement.canonical() != canonical) {
						trivial = false;
						break;
					}
				}
				if (trivial && replacement != null && phi.operands().size() > 1) {
					phi.replaceWith(replacement);
					block.phis().remove(index);
				}
			}
		}
	}
}
