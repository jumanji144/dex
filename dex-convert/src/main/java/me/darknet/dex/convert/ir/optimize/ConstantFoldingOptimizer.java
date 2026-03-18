package me.darknet.dex.convert.ir.optimize;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.CompareInstruction;
import me.darknet.dex.tree.definitions.instructions.UnaryInstruction;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

/**
 * Optimizer that folds known constant expressions into their results.
 */
public class ConstantFoldingOptimizer implements IrOptimizer {
	@Override
	public void optimizeMethod(@NotNull IrOptimizationContext context, @NotNull IrMethod method) {
		foldConstants(method);
	}

	protected void foldConstants(@NotNull IrMethod method) {
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				if (!(statement instanceof IrOp op) || !op.pure()) continue;
				IrConstant constant = fold(op);
				if (constant != null) {
					op.replaceWith(constant);
				}
			}
		}
	}

	protected IrConstant fold(@NotNull IrOp op) {
		return switch (op.payload()) {
			case BinaryInstruction instruction -> foldBinary(op, instruction);
			case BinaryLiteralInstruction instruction -> foldBinaryLiteral(op, instruction);
			case UnaryInstruction instruction -> foldUnary(op, instruction);
			case CompareInstruction instruction -> foldCompare(op, instruction);
			default -> null;
		};
	}

	protected IrConstant foldBinary(@NotNull IrOp op, @NotNull BinaryInstruction instruction) {
		Object left = op.inputs().get(0).canonical().constantValue();
		Object right = op.inputs().get(1).canonical().constantValue();
		if (!(left instanceof Number l) || !(right instanceof Number r)) return null;
		return switch (instruction.opcode()) {
			case Opcodes.ADD_INT -> new IrConstant(-1, Types.INT, l.intValue() + r.intValue(), false);
			case Opcodes.SUB_INT -> new IrConstant(-1, Types.INT, l.intValue() - r.intValue(), false);
			case Opcodes.MUL_INT -> new IrConstant(-1, Types.INT, l.intValue() * r.intValue(), false);
			case Opcodes.ADD_LONG -> new IrConstant(-1, Types.LONG, l.longValue() + r.longValue(), false);
			case Opcodes.SUB_LONG -> new IrConstant(-1, Types.LONG, l.longValue() - r.longValue(), false);
			case Opcodes.MUL_LONG -> new IrConstant(-1, Types.LONG, l.longValue() * r.longValue(), false);
			default -> null;
		};
	}

	protected IrConstant foldBinaryLiteral(@NotNull IrOp op, @NotNull BinaryLiteralInstruction instruction) {
		Object value = op.inputs().getFirst().canonical().constantValue();
		if (!(value instanceof Number number)) return null;
		return switch (instruction.opcode()) {
			case Opcodes.ADD_INT_LIT16, Opcodes.ADD_INT_LIT8 ->
					new IrConstant(-1, Types.INT, number.intValue() + instruction.constant(), false);
			case Opcodes.RSUB_INT, Opcodes.RSUB_INT_LIT8 ->
					new IrConstant(-1, Types.INT, instruction.constant() - number.intValue(), false);
			case Opcodes.MUL_INT_LIT16, Opcodes.MUL_INT_LIT8 ->
					new IrConstant(-1, Types.INT, number.intValue() * instruction.constant(), false);
			case Opcodes.AND_INT_LIT16, Opcodes.AND_INT_LIT8 ->
					new IrConstant(-1, Types.INT, number.intValue() & instruction.constant(), false);
			case Opcodes.OR_INT_LIT16, Opcodes.OR_INT_LIT8 ->
					new IrConstant(-1, Types.INT, number.intValue() | instruction.constant(), false);
			case Opcodes.XOR_INT_LIT16, Opcodes.XOR_INT_LIT8 ->
					new IrConstant(-1, Types.INT, number.intValue() ^ instruction.constant(), false);
			default -> null;
		};
	}

	protected IrConstant foldUnary(@NotNull IrOp op, @NotNull UnaryInstruction instruction) {
		Object value = op.inputs().getFirst().canonical().constantValue();
		if (!(value instanceof Number number))
			return null;
		return switch (instruction.opcode()) {
			case Opcodes.NEG_INT -> new IrConstant(-1, Types.INT, -number.intValue(), false);
			case Opcodes.NOT_INT -> new IrConstant(-1, Types.INT, ~number.intValue(), false);
			case Opcodes.NEG_LONG -> new IrConstant(-1, Types.LONG, -number.longValue(), false);
			case Opcodes.NOT_LONG -> new IrConstant(-1, Types.LONG, ~number.longValue(), false);
			default -> null;
		};
	}

	protected IrConstant foldCompare(@NotNull IrOp op, @NotNull CompareInstruction instruction) {
		Object left = op.inputs().get(0).canonical().constantValue();
		Object right = op.inputs().get(1).canonical().constantValue();
		if (!(left instanceof Number l) || !(right instanceof Number r))
			return null;
		int value = switch (instruction.opcode()) {
			case Opcodes.CMP_LONG -> Long.compare(l.longValue(), r.longValue());
			default -> Integer.compare((int) Math.signum(l.doubleValue() - r.doubleValue()), 0);
		};
		return new IrConstant(-1, Types.INT, value, value == 0);
	}
}
