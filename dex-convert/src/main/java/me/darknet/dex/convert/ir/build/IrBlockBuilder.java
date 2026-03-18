package me.darknet.dex.convert.ir.build;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.DexInstructionNode;
import me.darknet.dex.convert.ir.DexIrException;
import me.darknet.dex.convert.ir.IrBlock;
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

import java.util.ArrayList;
import java.util.List;

import static me.darknet.dex.convert.ConversionSupport.slotSize;
import static me.darknet.dex.convert.ir.build.IrBuildingUtils.*;

public class IrBlockBuilder {
	private final MethodMember method;
	private final Code code;
	private int nextValueId;

	public IrBlockBuilder(@NotNull IrBuilder builder) {
		method = builder.getInputMethod();
		code = method.getCode();
	}

	public void buildBlocks(@NotNull IrGraph graph) {
		initializePhis(graph.blocks());
		initializeEntryState(graph.entry());
		for (IrBlock block : graph.blocks())
			buildBlock(block);
	}

	private void initializePhis(@NotNull List<IrBlock> blocks) {
		for (int i = 1; i < blocks.size(); i++) {
			IrBlock block = blocks.get(i);
			if (block.predecessors().size() <= 1)
				continue;
			ensurePhis(block);
		}
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
			ensurePhis(block);
			for (IrPhi phi : block.phis()) {
				state[phi.register()] = phi;
			}
		}

		IrValue pendingResult = initialPendingResult(block);
		IrValue[] exceptionalState = null;
		for (DexInstructionNode node : block.dexInstructions()) {
			Instruction instruction = node.instruction();
			if (!(instruction instanceof MoveResultInstruction)) pendingResult = null;
			IrValue[] stateBeforeInstruction = null;
			if (!block.exceptionalSuccessors().isEmpty() && IrBuildingUtils.canThrow(instruction)) {
				stateBeforeInstruction = state.clone();
			}
			switch (instruction) {
				case ConstInstruction constInstruction ->
						state[constInstruction.register()] = constant(Types.INT, constInstruction.value(), constInstruction.value() == 0);
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
					IrOp op = new IrOp(nextValueId++, resultTypeForBinary(binaryInstruction.opcode()), IrOpKind.BINARY,
							List.of(readTyped(state, binaryInstruction.a(), operandTypeForBinary(binaryInstruction.opcode(), true)),
									readTyped(state, binaryInstruction.b(), operandTypeForBinary(binaryInstruction.opcode(), false))),
							binaryInstruction, true);
					op.register(binaryInstruction.dest());
					block.statements().add(op);
					state[binaryInstruction.dest()] = op;
				}
				case Binary2AddrInstruction binary2AddrInstruction -> {
					BinaryInstruction normalized = normalize(binary2AddrInstruction);
					IrOp op = new IrOp(nextValueId++, resultTypeForBinary(normalized.opcode()), IrOpKind.BINARY,
							List.of(readTyped(state, normalized.a(), operandTypeForBinary(normalized.opcode(), true)),
									readTyped(state, normalized.b(), operandTypeForBinary(normalized.opcode(), false))),
							normalized, true);
					op.register(binary2AddrInstruction.a());
					block.statements().add(op);
					state[binary2AddrInstruction.a()] = op;
				}
				case BinaryLiteralInstruction binaryLiteralInstruction -> {
					IrOp op = new IrOp(nextValueId++, Types.INT, IrOpKind.BINARY_LITERAL,
							List.of(readTyped(state, binaryLiteralInstruction.src(), Types.INT)), binaryLiteralInstruction, true);
					op.register(binaryLiteralInstruction.dest());
					block.statements().add(op);
					state[binaryLiteralInstruction.dest()] = op;
				}
				case UnaryInstruction unaryInstruction -> {
					IrOp op = new IrOp(nextValueId++, resultTypeForUnary(unaryInstruction.opcode()), IrOpKind.UNARY,
							List.of(readTyped(state, unaryInstruction.source(), operandTypeForUnary(unaryInstruction.opcode()))),
							unaryInstruction, true);
					op.register(unaryInstruction.dest());
					block.statements().add(op);
					state[unaryInstruction.dest()] = op;
				}
				case CompareInstruction compareInstruction -> {
					IrOp op = new IrOp(nextValueId++, Types.INT, IrOpKind.COMPARE,
							List.of(readTyped(state, compareInstruction.a(), operandTypeForCompare(compareInstruction.opcode())),
									readTyped(state, compareInstruction.b(), operandTypeForCompare(compareInstruction.opcode()))),
							compareInstruction, true);
					op.register(compareInstruction.dest());
					block.statements().add(op);
					state[compareInstruction.dest()] = op;
				}
				case ArrayLengthInstruction arrayLengthInstruction -> {
					IrOp op = new IrOp(nextValueId++, Types.INT, IrOpKind.ARRAY_LENGTH,
							List.of(read(state, arrayLengthInstruction.array())), arrayLengthInstruction, true);
					op.register(arrayLengthInstruction.dest());
					block.statements().add(op);
					state[arrayLengthInstruction.dest()] = op;
				}
				case ArrayInstruction arrayInstruction -> buildArrayInstruction(block, state, arrayInstruction);
				case CheckCastInstruction checkCastInstruction -> {
					IrOp op = new IrOp(nextValueId++, checkCastInstruction.type(), IrOpKind.CHECK_CAST,
							List.of(read(state, checkCastInstruction.register())), checkCastInstruction, true);
					op.register(checkCastInstruction.register());
					block.statements().add(op);
					state[checkCastInstruction.register()] = op;
				}
				case InstanceOfInstruction instanceOfInstruction -> {
					IrOp op = new IrOp(nextValueId++, Types.BOOLEAN, IrOpKind.INSTANCE_OF,
							List.of(read(state, instanceOfInstruction.register())), instanceOfInstruction, true);
					op.register(instanceOfInstruction.destination());
					block.statements().add(op);
					state[instanceOfInstruction.destination()] = op;
				}
				case NewInstanceInstruction newInstanceInstruction -> {
					IrOp op = new IrOp(nextValueId++, newInstanceInstruction.type(), IrOpKind.NEW_INSTANCE, List.of(), newInstanceInstruction, false);
					op.register(newInstanceInstruction.dest());
					block.statements().add(op);
					state[newInstanceInstruction.dest()] = op;
				}
				case NewArrayInstruction newArrayInstruction -> {
					IrOp op = new IrOp(nextValueId++, ConversionSupport.normalizeArrayType(newArrayInstruction.componentType()),
							IrOpKind.NEW_ARRAY, List.of(readTyped(state, newArrayInstruction.sizeRegister(), Types.INT)), newArrayInstruction, true);
					op.register(newArrayInstruction.dest());
					block.statements().add(op);
					state[newArrayInstruction.dest()] = op;
				}
				case FilledNewArrayInstruction filledNewArrayInstruction -> {
					IrOp op = new IrOp(nextValueId++, ConversionSupport.normalizeArrayType(filledNewArrayInstruction.componentType()),
							IrOpKind.FILLED_NEW_ARRAY, loadFilledInputs(state, filledNewArrayInstruction), filledNewArrayInstruction, true);
					block.statements().add(op);
					pendingResult = op;
				}
				case InvokeInstruction invokeInstruction -> {
					IrOp op = new IrOp(nextValueId++, invokeInstruction.type().returnType(), IrOpKind.INVOKE,
							loadInvokeInputs(state, invokeInstruction), invokeInstruction, false);
					block.statements().add(op);
					pendingResult = op;
				}
				case InvokeCustomInstruction invokeCustomInstruction -> {
					IrOp op = new IrOp(nextValueId++, invokeCustomInstruction.type().returnType(), IrOpKind.INVOKE_CUSTOM,
							loadInvokeInputs(state, invokeCustomInstruction), invokeCustomInstruction, false);
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
			if (stateBeforeInstruction != null) {
				exceptionalState = stateBeforeInstruction;
			}
		}

		if (block.terminator() == null) {
			block.terminator(new IrTerminator(IrTerminatorKind.GOTO, List.of(), null));
		}
		block.exitState(state.clone());
		if (exceptionalState != null) {
			block.exceptionalExitState(exceptionalState);
		}
		populatePhiInputs(block, state);
	}

	private @NotNull IrValue[] requireState(@NotNull IrBlock block) {
		IrValue[] state = block.exitState();
		if (state == null)
			throw new DexIrException("lift", method, "Missing predecessor state for " + block.debugName());
		return state;
	}

	private @NotNull IrValue[] requireInputState(@NotNull IrBlock predecessor, @NotNull IrBlock block) {
		if (predecessor.exceptionalSuccessors().contains(block) && predecessor.exceptionalExitState() != null) {
			return predecessor.exceptionalExitState();
		}
		return requireState(predecessor);
	}

	private boolean hasInputState(@NotNull IrBlock predecessor, @NotNull IrBlock block) {
		if (predecessor.exceptionalSuccessors().contains(block)) {
			return predecessor.exceptionalExitState() != null;
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
			IrOp op = new IrOp(nextValueId++, elementType, IrOpKind.ARRAY_GET,
					List.of(read(state, instruction.array()), readTyped(state, instruction.index(), Types.INT)), instruction, true);
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
			IrOp op = new IrOp(nextValueId++, instruction.type(), IrOpKind.INSTANCE_GET,
					List.of(read(state, instruction.instance())), instruction, true);
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
			IrOp op = new IrOp(nextValueId++, instruction.type(), IrOpKind.STATIC_GET, List.of(), instruction, true);
			op.register(instruction.value());
			block.statements().add(op);
			state[instruction.value()] = op;
		} else {
			block.statements().add(new IrEffect(IrEffectKind.STATIC_PUT, List.of(read(state, instruction.value())), instruction));
		}
	}

	private void populatePhiInputs(@NotNull IrBlock block, @NotNull IrValue[] normalState) {
		for (IrBlock successor : block.successors()) {
			populatePhiInputs(block, successor, normalState);
		}
		IrValue[] exceptionalState = block.exceptionalExitState() != null ? block.exceptionalExitState() : normalState;
		for (IrBlock successor : block.exceptionalSuccessors()) {
			populatePhiInputs(block, successor, exceptionalState);
		}
	}

	private void populatePhiInputs(@NotNull IrBlock predecessor, @NotNull IrBlock successor, @NotNull IrValue[] state) {
		if (successor.predecessors().size() <= 1) return;
		for (IrPhi phi : successor.phis()) {
			IrValue value = state[phi.register()];
			if (value == null)
				value = constant(Types.INT, 0, true);
			phi.putOperand(predecessor, value);
			if (phi.type().equals(Types.INT) && !value.type().equals(Types.INT)) {
				phi.type(value.type());
			}
		}
	}

	private @NotNull IrValue read(@NotNull IrValue[] state, int register) {
		IrValue value = state[register];
		if (value == null) {
			value = constant(Types.INT, 0, true);
			state[register] = value;
		}
		return value;
	}

	private @NotNull IrValue readTyped(@NotNull IrValue[] state, int register, @NotNull ClassType expectedType) {
		return adaptType(read(state, register), expectedType);
	}

	private @NotNull IrConstant constant(@NotNull ClassType type, @Nullable Object value, boolean zero) {
		return new IrConstant(nextValueId++, type, value, zero);
	}

	private void ensurePhis(@NotNull IrBlock block) {
		if (!block.phis().isEmpty()) return;
		for (int register = 0; register < code.getRegisters(); register++) {
			block.phis().add(new IrPhi(nextValueId++, block, register, Types.INT));
		}
	}
}
