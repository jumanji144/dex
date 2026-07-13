package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.ArrayLengthInstruction;
import me.darknet.dex.tree.definitions.instructions.Binary2AddrInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.MonitorInstruction;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import org.jetbrains.annotations.NotNull;

/**
 * Utility class for analyzing the semantics of DEX instructions.
 */
public final class InstructionSemantics {
	private static final int ANY = 1;
	private static final int ARITHMETIC = 1 << 1;
	private static final int NULL_POINTER = 1 << 2;
	private static final int ARRAY_INDEX = 1 << 3;
	private static final int ARRAY_STORE = 1 << 4;
	private static final int CLASS_CAST = 1 << 5;
	private static final int NEGATIVE_ARRAY_SIZE = 1 << 6;
	private static final int ILLEGAL_MONITOR_STATE = 1 << 7;
	private static final int LINKAGE = 1 << 8;
	private static final int OUT_OF_MEMORY = 1 << 9;
	private static final int RUNTIME = ARITHMETIC | NULL_POINTER | ARRAY_INDEX | ARRAY_STORE | CLASS_CAST
			| NEGATIVE_ARRAY_SIZE | ILLEGAL_MONITOR_STATE;
	private static final int ERRORS = LINKAGE | OUT_OF_MEMORY;

	private InstructionSemantics() {}

	public static boolean canThrow(@NotNull Instruction instruction) {
		return exceptionMask(instruction) != 0;
	}

	public static boolean canThrowToHandler(@NotNull Instruction instruction, @NotNull Handler handler) {
		int exceptions = exceptionMask(instruction);
		if (exceptions == 0) return false;
		if (handler.isCatchAll() || (exceptions & ANY) != 0) return true;

		String catchType = handler.exceptionType().internalName();
		return switch (catchType) {
			case "java/lang/Throwable" -> true;
			case "java/lang/Exception", "java/lang/RuntimeException" -> (exceptions & RUNTIME) != 0;
			case "java/lang/ArithmeticException" -> (exceptions & ARITHMETIC) != 0;
			case "java/lang/NullPointerException" -> (exceptions & NULL_POINTER) != 0;
			case "java/lang/IndexOutOfBoundsException", "java/lang/ArrayIndexOutOfBoundsException" ->
					(exceptions & ARRAY_INDEX) != 0;
			case "java/lang/ArrayStoreException" -> (exceptions & ARRAY_STORE) != 0;
			case "java/lang/ClassCastException" -> (exceptions & CLASS_CAST) != 0;
			case "java/lang/NegativeArraySizeException" -> (exceptions & NEGATIVE_ARRAY_SIZE) != 0;
			case "java/lang/IllegalMonitorStateException" -> (exceptions & ILLEGAL_MONITOR_STATE) != 0;
			case "java/lang/Error" -> (exceptions & ERRORS) != 0;
			case "java/lang/VirtualMachineError", "java/lang/OutOfMemoryError" ->
					(exceptions & OUT_OF_MEMORY) != 0;
			default -> isLinkageError(catchType) && (exceptions & LINKAGE) != 0;
		};
	}

	private static int exceptionMask(@NotNull Instruction instruction) {
		return switch (instruction) {
			case BinaryInstruction binary -> arithmeticException(binary.opcode());
			case Binary2AddrInstruction binary -> arithmeticException(binary.opcode());
			case BinaryLiteralInstruction binary -> arithmeticException(binary.opcode());
			case ArrayInstruction array -> NULL_POINTER | ARRAY_INDEX
					| (array.opcode() == Opcodes.APUT_OBJECT ? ARRAY_STORE : 0);
			case ArrayLengthInstruction ignored -> NULL_POINTER;
			case CheckCastInstruction ignored -> CLASS_CAST | LINKAGE;
			case FillArrayDataInstruction ignored -> NULL_POINTER | ARRAY_INDEX;
			case FilledNewArrayInstruction ignored -> LINKAGE | OUT_OF_MEMORY;
			case InstanceFieldInstruction ignored -> NULL_POINTER | LINKAGE;
			case InstanceOfInstruction ignored -> LINKAGE;
			case InvokeInstruction ignored -> ANY;
			case InvokeCustomInstruction ignored -> ANY;
			case ThrowInstruction ignored -> ANY;
			case MonitorInstruction ignored -> NULL_POINTER | ILLEGAL_MONITOR_STATE;
			case NewArrayInstruction ignored -> NEGATIVE_ARRAY_SIZE | LINKAGE | OUT_OF_MEMORY;
			case NewInstanceInstruction ignored -> LINKAGE | OUT_OF_MEMORY;
			case StaticFieldInstruction ignored -> LINKAGE;
			default -> 0;
		};
	}

	private static int arithmeticException(int opcode) {
		return switch (opcode) {
			case Opcodes.DIV_INT, Opcodes.REM_INT, Opcodes.DIV_LONG, Opcodes.REM_LONG,
					Opcodes.DIV_INT_2ADDR, Opcodes.REM_INT_2ADDR, Opcodes.DIV_LONG_2ADDR, Opcodes.REM_LONG_2ADDR,
					Opcodes.DIV_INT_LIT16, Opcodes.REM_INT_LIT16, Opcodes.DIV_INT_LIT8, Opcodes.REM_INT_LIT8 -> ARITHMETIC;
			default -> 0;
		};
	}

	private static boolean isLinkageError(@NotNull String type) {
		return switch (type) {
			case "java/lang/LinkageError", "java/lang/BootstrapMethodError", "java/lang/ClassCircularityError",
					"java/lang/ClassFormatError", "java/lang/UnsupportedClassVersionError",
					"java/lang/ExceptionInInitializerError", "java/lang/IncompatibleClassChangeError",
					"java/lang/AbstractMethodError", "java/lang/IllegalAccessError", "java/lang/InstantiationError",
					"java/lang/NoSuchFieldError", "java/lang/NoSuchMethodError", "java/lang/NoClassDefFoundError",
					"java/lang/UnsatisfiedLinkError", "java/lang/VerifyError" -> true;
			default -> false;
		};
	}
}
