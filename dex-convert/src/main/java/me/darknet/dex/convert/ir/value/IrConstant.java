package me.darknet.dex.convert.ir.value;

import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Represents a constant value in the IR, which can be a literal <i>(that can also be sourced from a static final field)</i>.
 */
public final class IrConstant extends IrValue {
	private final Object value;
	private final boolean zero;

	public IrConstant(int id, @NotNull ClassType type, @Nullable Object value, boolean zero) {
		super(id, type);
		this.value = value;
		this.zero = zero;
	}

	@Override
	public boolean isZeroConstant() {
		return zero;
	}

	@Override
	public Object constantValue() {
		return value;
	}

	@Override
	public String toString() {
		return Objects.toString(value);
	}
}
