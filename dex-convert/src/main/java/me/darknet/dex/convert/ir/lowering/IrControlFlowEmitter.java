package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.file.instructions.Opcodes;
import org.jetbrains.annotations.NotNull;

/**
 * Control-flow opcode policy shared by branch emission.
 */
final class IrControlFlowEmitter {
	private IrControlFlowEmitter() {}

	static boolean usesReferenceCompare(int opcode, @NotNull IrValue left, @NotNull IrValue right) {
		if (opcode != Opcodes.IF_EQ && opcode != Opcodes.IF_NE) return false;
		return ConversionSupport.isReferenceType(left.type())
				|| ConversionSupport.isReferenceType(right.type())
				|| (left.isZeroConstant() && ConversionSupport.isReferenceType(right.type()))
				|| (right.isZeroConstant() && ConversionSupport.isReferenceType(left.type()));
	}

	static int invertIfOpcode(int opcode) {
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

	static int invertIfZeroOpcode(int opcode) {
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
}

