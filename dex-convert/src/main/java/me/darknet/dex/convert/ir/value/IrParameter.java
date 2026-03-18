package me.darknet.dex.convert.ir.value;

import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a parameter of a method in the IR, which is assigned to a register and has a type.
 */
public final class IrParameter extends IrValue {
	private final int register;

	public IrParameter(int id, @NotNull ClassType type, int register) {
		super(id, type);
		this.register = register;
	}

	public int register() {
		return register;
	}

	@Override
	public String toString() {
		return "param(" + register + " : " + type() + ")";
	}
}
