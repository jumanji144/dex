package me.darknet.dex.convert.ir.statement;

import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents an operation statement in the IR, which performs a computation and produces a value.
 */
public final class IrOp extends IrValue implements IrStmt {
	private final IrOpKind kind;
	private final List<IrValue> inputs;
	private final Object payload;
	private final boolean pure;
	private int register = -1;

	public IrOp(int id, @NotNull ClassType type, @NotNull IrOpKind kind, @NotNull List<IrValue> inputs,
	            @Nullable Object payload, boolean pure) {
		super(id, type);
		this.kind = kind;
		this.inputs = inputs;
		this.payload = payload;
		this.pure = pure;
	}

	public @NotNull IrOpKind kind() {
		return kind;
	}

	public @NotNull List<IrValue> inputs() {
		return inputs;
	}

	public @Nullable Object payload() {
		return payload;
	}

	public boolean pure() {
		return pure;
	}

	public int register() {
		return register;
	}

	public boolean hasRegister() {
		return register >= 0;
	}

	public void register(int register) {
		if (this.register >= 0 && this.register != register) {
			throw new IllegalStateException("IrOp " + id() + " already bound to register " + this.register + ", cannot rebind to " + register);
		}
		this.register = register;
	}

	@Override
	public String toString() {
		if (payload != null)
			return payload.toString();
		return kind.name();
	}
}
