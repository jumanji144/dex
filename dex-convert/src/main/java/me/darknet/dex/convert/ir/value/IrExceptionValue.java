package me.darknet.dex.convert.ir.value;

import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an exception value in the IR, which is used to represent the exception object in a catch block.
 */
public final class IrExceptionValue extends IrValue {
	private int register = -1;

	public IrExceptionValue(int id, @NotNull ClassType type) {
		super(id, type);
	}

	public int register() {
		return register;
	}

	public boolean hasRegister() {
		return register >= 0;
	}

	public void register(int register) {
		if (this.register >= 0 && this.register != register) {
			throw new IllegalStateException(
					"IrExceptionValue " + id() + " already bound to register " + this.register + ", cannot rebind to " + register);
		}
		this.register = register;
	}

	@Override
	public String toString() {
		return "exception(" + type() + ")";
	}
}
