package me.darknet.dex.convert.ir.value;

import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An explicit result for an undefined or malformed source value. It survives
 * analysis and is materialized by a target backend using a typed fallback.
 */
public final class IrUnknown extends IrValue {
	private final @Nullable MethodMember sourceMethod;
	private final int dexOffset;
	private IrTypeKind expectedKind;

	public IrUnknown(int id, @NotNull ClassType expectedType, @NotNull IrTypeKind expectedKind,
					 @Nullable MethodMember sourceMethod, int dexOffset) {
		super(id, expectedType);
		irType(IrType.unknown(expectedType));
		this.expectedKind = expectedKind;
		this.sourceMethod = sourceMethod;
		this.dexOffset = dexOffset;
	}

	public @NotNull IrTypeKind expectedKind() {
		return expectedKind;
	}

	public @Nullable MethodMember sourceMethod() {
		return sourceMethod;
	}

	public int dexOffset() {
		return dexOffset;
	}

	public void refine(@NotNull ClassType expectedType) {
		irType(IrType.unknown(expectedType));
		type(expectedType);
		expectedKind = IrTypeKind.from(expectedType);
	}

	@Override
	public boolean isUnknown() {
		return true;
	}

	@Override
	public boolean stackOnly() {
		return false;
	}

	@Override
	public void stackOnly(boolean ignored) {
		// Unknowns must be materialized so that diagnostics and fallback policy
		// remain observable to every backend.
		super.stackOnly(false);
	}

	@Override
	public String toString() {
		return "unknown(" + expectedKind + " @" + dexOffset + ")";
	}
}
