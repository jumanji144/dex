package me.darknet.dex.convert.ir.build;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.ArrayLengthInstruction;
import me.darknet.dex.tree.definitions.instructions.Binary2AddrInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.MonitorInstruction;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static me.darknet.dex.convert.ConversionSupport.slotSize;

public class IrBuildingUtils {
	public static boolean canThrow(@NotNull Instruction instruction) {
		return switch (instruction) {
			case ArrayInstruction ignored -> true;
			case ArrayLengthInstruction ignored -> true;
			case CheckCastInstruction ignored -> true;
			case FillArrayDataInstruction ignored -> true;
			case FilledNewArrayInstruction ignored -> true;
			case InstanceFieldInstruction ignored -> true;
			case InstanceOfInstruction ignored -> true;
			case InvokeInstruction ignored -> true;
			case InvokeCustomInstruction ignored -> true;
			case MonitorInstruction ignored -> true;
			case NewArrayInstruction ignored -> true;
			case NewInstanceInstruction ignored -> true;
			case StaticFieldInstruction ignored -> true;
			case ThrowInstruction ignored -> true;
			default -> false;
		};
	}

	public static boolean canThrowToHandler(@NotNull Instruction instruction, @NotNull Handler handler) {
		if (handler.isCatchAll()) return canThrow(instruction);
		return catchesNullPointerException(handler)
				? canThrowNullPointerException(instruction)
				: canThrow(instruction);
	}

	public static boolean catchesNullPointerException(@NotNull Handler handler) {
		String catchType = handler.exceptionType().internalName();
		return switch (catchType) {
			case "java/lang/NullPointerException", "java/lang/RuntimeException",
			     "java/lang/Exception", "java/lang/Throwable" -> true;
			default -> false;
		};
	}

	public static boolean canThrowNullPointerException(@NotNull Instruction instruction) {
		return switch (instruction) {
			case ArrayInstruction ignored -> true;
			case ArrayLengthInstruction ignored -> true;
			case FillArrayDataInstruction ignored -> true;
			case InstanceFieldInstruction ignored -> true;
			case InvokeInstruction ignored -> true;
			case InvokeCustomInstruction ignored -> true;
			case MonitorInstruction ignored -> true;
			case ThrowInstruction ignored -> true;
			default -> false;
		};
	}

	public static @NotNull IrValue adaptType(@NotNull IrValue value, @NotNull ClassType expectedType) {
		IrValue canonical = value.canonical();
		if (!(canonical instanceof IrConstant constant))
			return canonical;
		ClassType currentType = constant.type();
		if (currentType.equals(expectedType)) return constant;
		if (constant.isZeroConstant()) {
			constant.type(expectedType);
			return constant;
		}
		if (currentType.equals(Types.INT) && canRetypeIntConstant(expectedType)) {
			constant.type(expectedType);
			return constant;
		}
		if (currentType.equals(Types.LONG) && canRetypeLongConstant(expectedType)) {
			constant.type(expectedType);
			return constant;
		}
		return constant;
	}

	public static boolean canRetypeIntConstant(@NotNull ClassType expectedType) {
		return ConversionSupport.isFloatType(expectedType)
				|| ConversionSupport.isReferenceType(expectedType)
				|| expectedType.equals(Types.BOOLEAN)
				|| expectedType.equals(Types.BYTE)
				|| expectedType.equals(Types.CHAR)
				|| expectedType.equals(Types.SHORT)
				|| expectedType.equals(Types.INT);
	}

	public static boolean canRetypeLongConstant(@NotNull ClassType expectedType) {
		return ConversionSupport.isDoubleType(expectedType) || ConversionSupport.isLongType(expectedType);
	}

	public static @NotNull ClassType arrayElementType(@NotNull ArrayInstruction instruction, @NotNull IrValue[] state) {
		ClassType inferred = null;
		IrValue arrayValue = state[instruction.array()];
		if (arrayValue != null && arrayValue.type() instanceof ArrayType arrayType) {
			inferred = arrayType.componentType();
		}
		return switch (instruction.opcode()) {
			case Opcodes.AGET, Opcodes.APUT ->
					inferred != null && ConversionSupport.isFloatType(inferred) ? Types.FLOAT : Types.INT;
			case Opcodes.AGET_WIDE, Opcodes.APUT_WIDE ->
					inferred != null && ConversionSupport.isDoubleType(inferred) ? Types.DOUBLE : Types.LONG;
			case Opcodes.AGET_OBJECT, Opcodes.APUT_OBJECT ->
					inferred instanceof ReferenceType ? inferred : Types.OBJECT;
			case Opcodes.AGET_BOOLEAN, Opcodes.APUT_BOOLEAN -> Types.BOOLEAN;
			case Opcodes.AGET_BYTE, Opcodes.APUT_BYTE -> Types.BYTE;
			case Opcodes.AGET_CHAR, Opcodes.APUT_CHAR -> Types.CHAR;
			case Opcodes.AGET_SHORT, Opcodes.APUT_SHORT -> Types.SHORT;
			default -> throw new IllegalArgumentException("Unsupported array opcode: " + instruction.opcode());
		};
	}

	public static @NotNull List<IrValue> loadFilledInputs(@NotNull IrValue[] state, @NotNull FilledNewArrayInstruction instruction) {
		List<IrValue> values = new ArrayList<>();
		ClassType elementType = ConversionSupport.arrayElementType(ConversionSupport.normalizeArrayType(instruction.componentType()));
		if (instruction.isRange()) {
			for (int register = instruction.first(); register <= instruction.last(); register++) {
				values.add(adaptType(state[register] == null ? new IrConstant(-1, Types.INT, 0, true) : state[register], elementType));
			}
		} else {
			for (int register : instruction.registers()) {
				values.add(adaptType(state[register] == null ? new IrConstant(-1, Types.INT, 0, true) : state[register], elementType));
			}
		}
		return values;
	}

	public static @NotNull List<IrValue> loadInvokeInputs(@NotNull IrValue[] state, @NotNull InvokeInstruction instruction) {
		// TODO: We need to sanity check index bounds for 'state[cursor]' as one test case has an odd invoke call that
		//  references registers that aren't actually defined in the method's register count.
		//  We should return a dummy constant since that is better than just crashing the converter.
		//  - Same idea applies below for the InvokeCustomInstruction version of this method.
		List<IrValue> values = new ArrayList<>();
		if (instruction.isRange()) {
			int cursor = instruction.first();
			if (instruction.opcode() != Invoke.STATIC) {
				values.add(adaptType(state[cursor] == null ? new IrConstant(-1, Types.INT, 0, true) : state[cursor], instruction.owner()));
				cursor++;
			}
			for (ClassType parameterType : instruction.type().parameterTypes()) {
				values.add(adaptType(state[cursor] == null ? new IrConstant(-1, Types.INT, 0, true) : state[cursor], parameterType));
				cursor += slotSize(parameterType);
			}
		} else {
			int cursor = 0;
			int[] arguments = instruction.arguments();
			if (instruction.opcode() != Invoke.STATIC) {
				values.add(adaptType(state[arguments[cursor]] == null ? new IrConstant(-1, Types.INT, 0, true) : state[arguments[cursor]],
						instruction.owner()));
				cursor++;
			}
			for (ClassType parameterType : instruction.type().parameterTypes()) {
				int register = arguments[cursor];
				values.add(adaptType(state[register] == null ? new IrConstant(-1, Types.INT, 0, true) : state[register], parameterType));
				cursor += slotSize(parameterType);
			}
		}
		return values;
	}

	public static @NotNull List<IrValue> loadInvokeInputs(@NotNull IrValue[] state, @NotNull InvokeCustomInstruction instruction) {
		List<IrValue> values = new ArrayList<>();
		if (instruction.isRange()) {
			int cursor = instruction.first();
			for (ClassType parameterType : instruction.type().parameterTypes()) {
				values.add(adaptType(state[cursor] == null ? new IrConstant(-1, Types.INT, 0, true) : state[cursor], parameterType));
				cursor += slotSize(parameterType);
			}
		} else {
			int cursor = 0;
			int[] arguments = instruction.argumentRegisters();
			for (ClassType parameterType : instruction.type().parameterTypes()) {
				int register = arguments[cursor];
				values.add(adaptType(state[register] == null ? new IrConstant(-1, Types.INT, 0, true) : state[register], parameterType));
				cursor += slotSize(parameterType);
			}
		}
		return values;
	}

	public static @NotNull BinaryInstruction normalize(@NotNull Binary2AddrInstruction instruction) {
		return new BinaryInstruction(normalize2AddrOpcode(instruction.opcode()), instruction.a(), instruction.a(), instruction.b());
	}

	public static int normalize2AddrOpcode(int opcode) {
		return switch (opcode) {
			case Opcodes.ADD_INT_2ADDR -> Opcodes.ADD_INT;
			case Opcodes.SUB_INT_2ADDR -> Opcodes.SUB_INT;
			case Opcodes.MUL_INT_2ADDR -> Opcodes.MUL_INT;
			case Opcodes.DIV_INT_2ADDR -> Opcodes.DIV_INT;
			case Opcodes.REM_INT_2ADDR -> Opcodes.REM_INT;
			case Opcodes.AND_INT_2ADDR -> Opcodes.AND_INT;
			case Opcodes.OR_INT_2ADDR -> Opcodes.OR_INT;
			case Opcodes.XOR_INT_2ADDR -> Opcodes.XOR_INT;
			case Opcodes.SHL_INT_2ADDR -> Opcodes.SHL_INT;
			case Opcodes.SHR_INT_2ADDR -> Opcodes.SHR_INT;
			case Opcodes.USHR_INT_2ADDR -> Opcodes.USHR_INT;
			case Opcodes.ADD_LONG_2ADDR -> Opcodes.ADD_LONG;
			case Opcodes.SUB_LONG_2ADDR -> Opcodes.SUB_LONG;
			case Opcodes.MUL_LONG_2ADDR -> Opcodes.MUL_LONG;
			case Opcodes.DIV_LONG_2ADDR -> Opcodes.DIV_LONG;
			case Opcodes.REM_LONG_2ADDR -> Opcodes.REM_LONG;
			case Opcodes.AND_LONG_2ADDR -> Opcodes.AND_LONG;
			case Opcodes.OR_LONG_2ADDR -> Opcodes.OR_LONG;
			case Opcodes.XOR_LONG_2ADDR -> Opcodes.XOR_LONG;
			case Opcodes.SHL_LONG_2ADDR -> Opcodes.SHL_LONG;
			case Opcodes.SHR_LONG_2ADDR -> Opcodes.SHR_LONG;
			case Opcodes.USHR_LONG_2ADDR -> Opcodes.USHR_LONG;
			case Opcodes.ADD_FLOAT_2ADDR -> Opcodes.ADD_FLOAT;
			case Opcodes.SUB_FLOAT_2ADDR -> Opcodes.SUB_FLOAT;
			case Opcodes.MUL_FLOAT_2ADDR -> Opcodes.MUL_FLOAT;
			case Opcodes.DIV_FLOAT_2ADDR -> Opcodes.DIV_FLOAT;
			case Opcodes.REM_FLOAT_2ADDR -> Opcodes.REM_FLOAT;
			case Opcodes.ADD_DOUBLE_2ADDR -> Opcodes.ADD_DOUBLE;
			case Opcodes.SUB_DOUBLE_2ADDR -> Opcodes.SUB_DOUBLE;
			case Opcodes.MUL_DOUBLE_2ADDR -> Opcodes.MUL_DOUBLE;
			case Opcodes.DIV_DOUBLE_2ADDR -> Opcodes.DIV_DOUBLE;
			case Opcodes.REM_DOUBLE_2ADDR -> Opcodes.REM_DOUBLE;
			default -> throw new IllegalArgumentException("Unsupported 2addr opcode: " + opcode);
		};
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
			case Opcodes.NEG_DOUBLE, Opcodes.INT_TO_DOUBLE, Opcodes.LONG_TO_DOUBLE, Opcodes.FLOAT_TO_DOUBLE ->
					Types.DOUBLE;
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
			case Opcodes.NEG_DOUBLE, Opcodes.DOUBLE_TO_INT, Opcodes.DOUBLE_TO_LONG, Opcodes.DOUBLE_TO_FLOAT ->
					Types.DOUBLE;
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
}
