package me.darknet.dex.convert.ir.value;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a phi node in the IR, which is used to merge values from different control flow paths.
 */
public final class IrPhi extends IrValue {
	private final IrBlock block;
	private final int register;
	private final Map<IrBlock, IrValue> operands = new HashMap<>();

	public IrPhi(int id, @NotNull IrBlock block, int register, @NotNull ClassType type) {
		super(id, type);
		this.block = block;
		this.register = register;
	}

	public @NotNull IrBlock block() {
		return block;
	}

	public int register() {
		return register;
	}

	public @NotNull Map<IrBlock, IrValue> operands() {
		return operands;
	}

	public void putOperand(@NotNull IrBlock predecessor, @NotNull IrValue value) {
		operands.put(predecessor, value);
	}

	@Override
	public String toString() {
		return "phi( " + register + " : " + type() + " )";
	}
}
