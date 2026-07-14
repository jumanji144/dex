package me.darknet.dex.convert.ir.value;

import me.darknet.dex.convert.ir.analysis.IrTypeHierarchy;
import me.darknet.dex.convert.ir.analysis.IrTypeResolver;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.ReferenceType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A source-neutral type-lattice element with optional exact reference refinement. */
public record IrType(@NotNull IrTypeKind kind, @Nullable ClassType exactReference,
					 @NotNull IrNullability nullability) {
	public static IrType from(@NotNull ClassType type) {
		IrTypeKind kind = IrTypeKind.from(type);
		return new IrType(kind, kind == IrTypeKind.REFERENCE ? type : null,
				kind == IrTypeKind.REFERENCE ? IrNullability.UNKNOWN : IrNullability.NOT_NULL);
	}

	public static IrType unknown(@Nullable ClassType expected) {
		return new IrType(IrTypeKind.UNKNOWN, null, IrNullability.UNKNOWN);
	}

	public static IrType from(@NotNull IrValue value) {
		if (value instanceof IrUnknown) return value.irType();
		if (value instanceof IrConstant constant && constant.constantValue() == null
				&& IrTypeKind.from(value.type()) == IrTypeKind.REFERENCE)
			return new IrType(IrTypeKind.REFERENCE, null, IrNullability.NULL);
		return value.irType();
	}

	public @NotNull ClassType materializedType() {
		return exactReference != null ? exactReference : kind.representative();
	}

	public static @NotNull IrType join(@NotNull IrType left, @NotNull IrType right) {
		return join(left, right, IrTypeResolver.EMPTY);
	}

	public static @NotNull IrType join(@NotNull IrType left, @NotNull IrType right,
	                                  @NotNull IrTypeResolver resolver) {
		if (left.kind == IrTypeKind.BOTTOM) return right;
		if (right.kind == IrTypeKind.BOTTOM) return left;
		if (left.kind == IrTypeKind.UNKNOWN) return right;
		if (right.kind == IrTypeKind.UNKNOWN) return left;
		IrTypeKind kind = left.kind == right.kind ? left.kind : joinKind(left.kind, right.kind);
		ClassType exact = null;
		if (kind == IrTypeKind.REFERENCE) {
			if (left.exactReference == null) exact = right.exactReference;
			else if (right.exactReference == null) exact = left.exactReference;
			else if (left.exactReference.equals(right.exactReference)) exact = left.exactReference;
			else if (left.exactReference instanceof ReferenceType leftReference
					&& right.exactReference instanceof ReferenceType rightReference)
				exact = IrTypeHierarchy.commonSupertype(leftReference, rightReference, resolver);
		}
		IrNullability nullability = left.nullability == right.nullability
				? left.nullability : IrNullability.MAYBE_NULL;
		return new IrType(kind, exact, nullability);
	}

	private static IrTypeKind joinKind(IrTypeKind left, IrTypeKind right) {
		if (left == IrTypeKind.TOP || right == IrTypeKind.TOP) return IrTypeKind.TOP;
		if (left == IrTypeKind.REFERENCE && right == IrTypeKind.REFERENCE) return IrTypeKind.REFERENCE;
		return IrTypeKind.TOP;
	}
}
