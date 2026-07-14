package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrEffectKind;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrOpKind;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.value.IrType;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.Binary2AddrInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.Types;
import me.darknet.dex.file.instructions.Opcodes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable, shared semantic metadata for an IR instruction.  Frontends may
 * use it to validate construction and every backend may use the same input
 * contract while selecting target instructions.
 */
public record IrInstructionSemantics(
		@NotNull List<IrTypeConstraint> inputs,
		@NotNull IrType result,
		@NotNull IrResultTypeRule resultRule,
		@NotNull Effect effect,
		int throwMask,
		@NotNull String constructionId,
		@NotNull String loweringId,
		boolean complete) {
	public enum Effect {
		PURE,
		MAY_THROW,
		OBSERVABLE
	}

	public IrInstructionSemantics {
		inputs = List.copyOf(inputs);
	}

	public static @NotNull IrInstructionSemantics forOperation(@NotNull IrOp op) {
		return forOperation(op.kind(), op.payload(), op.type(), op.inputs());
	}

	/** Builds a contract whose result type is derived entirely from the DEX payload. */
	public static @NotNull IrInstructionSemantics forOperation(@NotNull IrOpKind kind,
	                                                          @NotNull Object payload,
	                                                          int inputCount) {
		return forOperation(kind, payload, resultType(kind, payload), inputCount);
	}

	/** Builds an array contract after the receiver value has been materialized. */
	public static @NotNull IrInstructionSemantics forOperation(@NotNull IrOpKind kind,
	                                                          @NotNull Object payload,
	                                                          @NotNull ClassType type,
	                                                          @NotNull List<IrValue> values) {
		IrInstructionSemantics base = forOperation(kind, payload, type, values.size());
		if (kind != IrOpKind.ARRAY_GET || values.isEmpty()) return base;
		IrValue receiver = values.getFirst().canonical();
		if (!(receiver.type() instanceof ArrayType arrayType)) return base;
		List<IrTypeConstraint> constraints = new ArrayList<>(base.inputs());
		constraints.set(0, c(arrayType, "array"));
		return new IrInstructionSemantics(constraints, base.result(), base.resultRule(), base.effect(),
				base.throwMask(), base.constructionId(), base.loweringId(), base.complete());
	}

	public static @NotNull IrInstructionSemantics forOperation(@NotNull IrOpKind kind,
	                                                          @NotNull Object payload,
	                                                          @NotNull ClassType type,
	                                                          int inputCount) {
		List<IrTypeConstraint> constraints = new ArrayList<>();
		IrResultTypeRule resultRule = IrResultTypeRule.FIXED;
		boolean complete = true;
		switch (kind) {
			case BINARY, BINARY_LITERAL -> {
				int opcode = opcode(payload);
				constraints.add(c(typeForBinary(opcode, true), "left"));
				if (kind == IrOpKind.BINARY) constraints.add(c(typeForBinary(opcode, false), "right"));
				resultRule = kind == IrOpKind.BINARY_LITERAL ? IrResultTypeRule.INTEGER : IrResultTypeRule.FIXED;
			}
			case UNARY -> {
				constraints.add(c(typeForUnary(payload), "value"));
				resultRule = IrResultTypeRule.FIXED;
			}
			case COMPARE -> {
				ClassType operand = typeForCompare(payload);
				constraints.add(c(operand, "left"));
				constraints.add(c(operand, "right"));
				resultRule = IrResultTypeRule.BOOLEAN;
			}
			case ARRAY_LENGTH, CHECK_CAST, INSTANCE_OF, INSTANCE_GET -> constraints.add(c(Types.OBJECT, "receiver"));
			case ARRAY_GET -> {
				constraints.add(c(Types.OBJECT, "array"));
				constraints.add(c(Types.INT, "index"));
				resultRule = IrResultTypeRule.ARRAY_ELEMENT;
			}
			case NEW_ARRAY -> constraints.add(c(Types.INT, "size"));
			case FILLED_NEW_ARRAY -> {
				ClassType element = type instanceof me.darknet.dex.tree.type.ArrayType array
						? array.componentType() : Types.OBJECT;
				for (int i = 0; i < inputCount; i++) constraints.add(c(element, "element"));
			}
			case INVOKE, INVOKE_CUSTOM -> {
				addInvokeConstraints(constraints, payload);
				resultRule = IrResultTypeRule.INVOKE_RETURN;
			}
			case NEW_INSTANCE, STATIC_GET -> { }
		}
		if (constraints.size() != inputCount || !payloadMatches(kind, payload)) complete = false;
		int mask = throwMask(payload);
		return new IrInstructionSemantics(constraints, IrType.from(type), resultRule,
				effectFor(kind, mask), mask, kind.name(), kind.name(), complete);
	}

	/**
	 * Contract for DEX instructions which are consumed while constructing IR
	 * (constants, moves, move-results, labels, and similar frontend-only nodes).
	 * They still get stable semantic identity and throw classification so the
	 * frontend does not need a second semantic authority.
	 */
	public static @NotNull IrInstructionSemantics forConstruction(@NotNull Instruction instruction) {
		int mask = InstructionSemantics.throwMask(instruction);
		IrType result = switch (instruction) {
			case me.darknet.dex.tree.definitions.instructions.ConstWideInstruction ignored -> IrType.from(Types.LONG);
			case me.darknet.dex.tree.definitions.instructions.ConstStringInstruction ignored -> IrType.from(Types.instanceType(String.class));
			case me.darknet.dex.tree.definitions.instructions.ConstTypeInstruction ignored -> IrType.from(Types.instanceType(Class.class));
			case me.darknet.dex.tree.definitions.instructions.ConstMethodHandleInstruction ignored -> IrType.from(Types.instanceType(java.lang.invoke.MethodHandle.class));
			case me.darknet.dex.tree.definitions.instructions.ConstMethodTypeInstruction ignored -> IrType.from(Types.instanceType(java.lang.invoke.MethodType.class));
			default -> IrType.unknown(null);
		};
		String id = "dex." + instruction.getClass().getSimpleName();
		return new IrInstructionSemantics(List.of(), result, result.kind() == IrTypeKind.UNKNOWN
				? IrResultTypeRule.UNKNOWN : IrResultTypeRule.FIXED,
				mask == 0 ? Effect.PURE : Effect.MAY_THROW, mask, id, id, true);
	}

	public static @NotNull ClassType resultTypeForBinary(int opcode) {
		if (opcode >= Opcodes.ADD_LONG && opcode <= Opcodes.USHR_LONG) return Types.LONG;
		if (opcode >= Opcodes.ADD_FLOAT && opcode <= Opcodes.REM_FLOAT) return Types.FLOAT;
		if (opcode >= Opcodes.ADD_DOUBLE && opcode <= Opcodes.REM_DOUBLE) return Types.DOUBLE;
		return Types.INT;
	}

	public static @NotNull ClassType resultTypeForUnary(int opcode) {
		return switch (opcode) {
			case Opcodes.NEG_LONG, Opcodes.NOT_LONG, Opcodes.INT_TO_LONG, Opcodes.FLOAT_TO_LONG,
					Opcodes.DOUBLE_TO_LONG -> Types.LONG;
			case Opcodes.NEG_FLOAT, Opcodes.INT_TO_FLOAT, Opcodes.LONG_TO_FLOAT, Opcodes.DOUBLE_TO_FLOAT -> Types.FLOAT;
			case Opcodes.NEG_DOUBLE, Opcodes.INT_TO_DOUBLE, Opcodes.LONG_TO_DOUBLE, Opcodes.FLOAT_TO_DOUBLE -> Types.DOUBLE;
			default -> Types.INT;
		};
	}

	public static @NotNull ClassType operandTypeForBinary(int opcode, boolean leftOperand) {
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

	public static @NotNull ClassType operandTypeForUnary(int opcode) {
		return switch (opcode) {
			case Opcodes.NEG_LONG, Opcodes.NOT_LONG, Opcodes.LONG_TO_INT, Opcodes.LONG_TO_FLOAT,
					Opcodes.LONG_TO_DOUBLE -> Types.LONG;
			case Opcodes.NEG_FLOAT, Opcodes.FLOAT_TO_INT, Opcodes.FLOAT_TO_LONG, Opcodes.FLOAT_TO_DOUBLE -> Types.FLOAT;
			case Opcodes.NEG_DOUBLE, Opcodes.DOUBLE_TO_INT, Opcodes.DOUBLE_TO_LONG, Opcodes.DOUBLE_TO_FLOAT -> Types.DOUBLE;
			default -> Types.INT;
		};
	}

	public static @NotNull ClassType operandTypeForCompare(int opcode) {
		return switch (opcode) {
			case Opcodes.CMP_LONG -> Types.LONG;
			case Opcodes.CMPL_FLOAT, Opcodes.CMPG_FLOAT -> Types.FLOAT;
			case Opcodes.CMPL_DOUBLE, Opcodes.CMPG_DOUBLE -> Types.DOUBLE;
			default -> Types.INT;
		};
	}

	public static @NotNull IrInstructionSemantics forEffect(@NotNull IrEffect effect) {
		List<IrTypeConstraint> constraints = new ArrayList<>();
			switch (effect.kind()) {
			case ARRAY_PUT -> {
				ClassType arrayType = effect.inputs().isEmpty() ? Types.OBJECT : effect.inputs().getFirst().type();
				constraints.add(c(arrayType, "array"));
				constraints.add(c(Types.INT, "index"));
				ClassType elementType = arrayType instanceof ArrayType array ? array.componentType()
						: effect.inputs().size() > 2 ? effect.inputs().get(2).type() : Types.OBJECT;
				constraints.add(c(elementType, "value"));
			}
			case INSTANCE_PUT -> {
				ClassType owner = effect.payload() instanceof me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction field
						? field.owner() : Types.OBJECT;
				ClassType fieldType = effect.payload() instanceof me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction field
						? field.type() : effect.inputs().size() > 1 ? effect.inputs().get(1).type() : Types.OBJECT;
				constraints.add(c(owner, "receiver"));
				constraints.add(c(fieldType, "value"));
			}
			case STATIC_PUT -> constraints.add(c(effect.payload() instanceof me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction field
					? field.type() : effect.inputs().isEmpty() ? Types.OBJECT : effect.inputs().getFirst().type(), "value"));
			case FILL_ARRAY_DATA -> constraints.add(c(Types.OBJECT, "array"));
			case MONITOR -> constraints.add(c(Types.OBJECT, "monitor"));
		}
		int mask = throwMask(effect.payload());
		return new IrInstructionSemantics(constraints, IrType.from(Types.VOID), IrResultTypeRule.VOID,
				mask == 0 ? Effect.OBSERVABLE : Effect.MAY_THROW, mask, effect.kind().name(), effect.kind().name(),
				constraints.size() == effect.inputs().size());
	}

	public static @NotNull IrInstructionSemantics forThrowingInstruction(@NotNull Instruction instruction) {
		int mask = InstructionSemantics.throwMask(instruction);
		return new IrInstructionSemantics(List.of(), IrType.from(Types.VOID), IrResultTypeRule.VOID,
				Effect.MAY_THROW, mask, instruction.getClass().getSimpleName(), "exception-edge", true);
	}

	public static boolean canThrow(@NotNull Instruction instruction) {
		return forThrowingInstruction(instruction).throwMask() != 0;
	}

	public static boolean canThrowToHandler(@NotNull Instruction instruction, @NotNull Handler handler) {
		return InstructionSemantics.canThrowToHandler(instruction, handler);
	}

	public static @NotNull IrInstructionSemantics forTerminator(@NotNull IrTerminator terminator) {
		List<IrTypeConstraint> constraints = new ArrayList<>();
		IrResultTypeRule resultRule = IrResultTypeRule.VOID;
		boolean complete = true;
		 switch (terminator.kind()) {
			case IF -> {
				ClassType left = branchInputType(terminator, 0);
				ClassType right = branchInputType(terminator, 1);
				constraints.add(c(left, "left"));
				constraints.add(c(right, "right"));
			}
			case IF_ZERO -> constraints.add(c(branchInputType(terminator, 0), "value"));
			case SWITCH -> constraints.add(c(Types.INT, "value"));
			case RETURN -> {
				if (terminator.payload() instanceof ReturnInstruction instruction
						&& instruction.type() != Return.VOID)
					constraints.add(c(IrType.from(terminator.inputs().isEmpty()
							? Types.OBJECT : terminator.inputs().getFirst().type()), "return"));
			}
			case THROW -> constraints.add(c(Types.instanceType(Throwable.class), "throwable"));
			case GOTO -> { }
		}
		complete = constraints.size() == terminator.inputs().size()
				|| terminator.kind() == IrTerminatorKind.GOTO;
		return new IrInstructionSemantics(constraints, IrType.from(Types.VOID), resultRule,
				terminator.kind() == IrTerminatorKind.THROW ? Effect.MAY_THROW : Effect.PURE,
				terminator.payload() == null ? 0 : throwMask(terminator.payload()),
				terminator.kind().name(), terminator.kind().name(), complete);
	}

	private static @NotNull ClassType branchInputType(@NotNull IrTerminator terminator, int index) {
		if (index >= terminator.inputs().size()) return Types.INT;
		IrValue value = terminator.inputs().get(index);
		boolean referenceComparison = terminator.payload() instanceof BranchInstruction instruction
				&& (instruction.opcode() == Opcodes.IF_EQ || instruction.opcode() == Opcodes.IF_NE);
		boolean referenceZeroTest = terminator.payload() instanceof BranchZeroInstruction instruction
				&& (instruction.opcode() == Opcodes.IF_EQZ || instruction.opcode() == Opcodes.IF_NEZ);
		if ((referenceComparison || referenceZeroTest)
				&& (value.isZeroConstant() || ConversionSupport.isReferenceType(value.type()))) return value.type();
		return Types.INT;
	}

	private static void addInvokeConstraints(List<IrTypeConstraint> out, Object payload) {
		if (payload instanceof InvokeInstruction invoke) {
			if (invoke.opcode() != me.darknet.dex.tree.definitions.instructions.Invoke.STATIC)
				out.add(c(invoke.owner(), "receiver"));
			for (ClassType parameter : invoke.type().parameterTypes()) out.add(c(parameter, "argument"));
		} else if (payload instanceof InvokeCustomInstruction invoke) {
			for (ClassType parameter : invoke.type().parameterTypes()) out.add(c(parameter, "argument"));
		}
	}

	private static IrTypeConstraint c(ClassType type, String role) { return new IrTypeConstraint(IrType.from(type), role); }
	private static IrTypeConstraint c(IrType type, String role) { return new IrTypeConstraint(type, role); }
	private static int throwMask(Object payload) { return payload instanceof Instruction i ? InstructionSemantics.throwMask(i) : 0; }
	private static int opcode(Object payload) {
		if (payload == null) return -1;
		return switch (payload) {
			case BinaryInstruction i -> i.opcode();
			case Binary2AddrInstruction i -> i.opcode();
			case BinaryLiteralInstruction i -> i.opcode();
			default -> -1;
		};
	}
	private static ClassType typeForBinary(int opcode, boolean left) {
		if (opcode < 0) return Types.INT;
		return operandTypeForBinary(opcode, left);
	}
	private static ClassType typeForUnary(Object payload) {
		return payload instanceof me.darknet.dex.tree.definitions.instructions.UnaryInstruction i
				? operandTypeForUnary(i.opcode()) : Types.INT;
	}
	private static ClassType typeForCompare(Object payload) {
		return payload instanceof me.darknet.dex.tree.definitions.instructions.CompareInstruction i
				? operandTypeForCompare(i.opcode()) : Types.INT;
	}
	private static Effect effectFor(IrOpKind kind, int throwMask) {
		return switch (kind) {
			case BINARY, BINARY_LITERAL, UNARY, COMPARE, INSTANCE_OF ->
					throwMask != 0 ? Effect.MAY_THROW : Effect.PURE;
			case ARRAY_LENGTH, CHECK_CAST, NEW_INSTANCE, NEW_ARRAY, FILLED_NEW_ARRAY, ARRAY_GET -> Effect.MAY_THROW;
			case INSTANCE_GET, STATIC_GET, INVOKE, INVOKE_CUSTOM -> Effect.OBSERVABLE;
		};
	}

	private static boolean payloadMatches(IrOpKind kind, Object payload) {
		return switch (kind) {
			case BINARY -> payload instanceof BinaryInstruction || payload instanceof Binary2AddrInstruction;
			case BINARY_LITERAL -> payload instanceof BinaryLiteralInstruction;
			case UNARY -> payload instanceof me.darknet.dex.tree.definitions.instructions.UnaryInstruction;
			case COMPARE -> payload instanceof me.darknet.dex.tree.definitions.instructions.CompareInstruction;
			case ARRAY_LENGTH -> payload instanceof me.darknet.dex.tree.definitions.instructions.ArrayLengthInstruction;
			case CHECK_CAST -> payload instanceof me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
			case INSTANCE_OF -> payload instanceof me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
			case NEW_INSTANCE -> payload instanceof me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
			case NEW_ARRAY -> payload instanceof me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
			case FILLED_NEW_ARRAY -> payload instanceof me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction;
			case ARRAY_GET -> payload instanceof ArrayInstruction;
			case INSTANCE_GET -> payload instanceof me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
			case STATIC_GET -> payload instanceof me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
			case INVOKE -> payload instanceof InvokeInstruction;
			case INVOKE_CUSTOM -> payload instanceof InvokeCustomInstruction;
		};
	}

	private static @NotNull ClassType resultType(@NotNull IrOpKind kind, @NotNull Object payload) {
		return switch (kind) {
			case BINARY -> payload instanceof BinaryInstruction instruction
					? resultTypeForBinary(instruction.opcode()) : Types.INT;
			case BINARY_LITERAL, COMPARE, ARRAY_LENGTH -> Types.INT;
			case UNARY -> payload instanceof me.darknet.dex.tree.definitions.instructions.UnaryInstruction instruction
					? resultTypeForUnary(instruction.opcode()) : Types.INT;
			case CHECK_CAST -> payload instanceof me.darknet.dex.tree.definitions.instructions.CheckCastInstruction instruction
					? instruction.type() : Types.OBJECT;
			case INSTANCE_OF -> Types.BOOLEAN;
			case NEW_INSTANCE -> payload instanceof me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction instruction
					? instruction.type() : Types.OBJECT;
			case NEW_ARRAY -> payload instanceof me.darknet.dex.tree.definitions.instructions.NewArrayInstruction instruction
					? ConversionSupport.normalizeArrayType(instruction.componentType()) : Types.OBJECT;
			case FILLED_NEW_ARRAY -> payload instanceof me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction instruction
					? ConversionSupport.normalizeArrayType(instruction.componentType()) : Types.OBJECT;
			case ARRAY_GET -> Types.OBJECT;
			case INSTANCE_GET -> payload instanceof me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction instruction
					? instruction.type() : Types.OBJECT;
			case STATIC_GET -> payload instanceof me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction instruction
					? instruction.type() : Types.OBJECT;
			case INVOKE -> payload instanceof InvokeInstruction instruction ? instruction.type().returnType() : Types.VOID;
			case INVOKE_CUSTOM -> payload instanceof InvokeCustomInstruction instruction ? instruction.type().returnType() : Types.VOID;
		};
	}
}
