package me.darknet.dex.convert.ir.value;

import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a value in the IR, which can be an operation, a phi node, a constant, or any other kind of value.
 */
public abstract class IrValue {
	private final int id;
	private ClassType type;
	private IrType latticeType;
	private int local = -1;
	private boolean stackOnly;
	private IrValue replacement;

	protected IrValue(int id, @NotNull ClassType type) {
		this.id = id;
		this.type = type;
		this.latticeType = IrType.from(type);
	}

	public int id() {
		return id;
	}

	public @NotNull ClassType type() {
		return type;
	}

	public void type(@NotNull ClassType type) {
		this.type = type;
		this.latticeType = IrType.from(type);
	}

	/** The source-neutral type used by inference. */
	public @NotNull IrType irType() {
		return latticeType;
	}

	/** Updates inference state without discarding nullability or exact-reference information. */
	public void irType(@NotNull IrType type) {
		this.latticeType = type;
	}

	public boolean isImprecise() {
		return latticeType.kind() == IrTypeKind.TOP || latticeType.kind() == IrTypeKind.UNKNOWN
				|| (latticeType.kind() == IrTypeKind.REFERENCE && latticeType.exactReference() == null);
	}

	public int local() {
		return local;
	}

	public boolean hasLocal() {
		return local >= 0;
	}

	public void local(int local) {
		this.local = local;
	}

	public boolean stackOnly() {
		return stackOnly;
	}

	public void stackOnly(boolean stackOnly) {
		this.stackOnly = stackOnly;
	}

	public @NotNull IrValue canonical() {
		if (replacement == null)
			return this;
		replacement = replacement.canonical();
		return replacement;
	}

	public void replaceWith(@NotNull IrValue value) {
		replacement = value.canonical();
	}

	public boolean isZeroConstant() {
		return false;
	}

	public @Nullable Object constantValue() {
		return null;
	}

	/** Unknown values are deliberately not constants and cannot be stack-only. */
	public boolean isUnknown() {
		return false;
	}
}
