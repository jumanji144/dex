package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.value.IrType;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Immutable facts about one canonical IR value for JVM lowering proofs. */
record JvmValueFacts(
		@NotNull IrValue value,
		@NotNull IrType latticeType,
		@NotNull IrTypeKind category,
		boolean unknown,
		boolean imprecise,
		boolean constant,
		boolean live,
		int useCount,
		int local,
		JvmLiveness.@NotNull Interval interval,
		@Nullable IrBlock definingBlock,
		int definitionIndex,
		@NotNull List<String> protectedRangeProfile,
		@NotNull List<String> exceptionalProfile,
		@NotNull List<String> resourceLayerProfile) {
	static @NotNull JvmValueFacts of(@NotNull IrValue value,
	                                @NotNull LoweringUseGraph useGraph,
	                                @NotNull JvmLiveness.Interval interval,
	                                @Nullable IrBlock definingBlock,
	                                int definitionIndex,
	                                @Nullable JvmBlockFacts blockFacts) {
		IrValue canonical = value.canonical();
		return new JvmValueFacts(canonical, canonical.irType(), canonical.irType().kind(),
				canonical instanceof IrUnknown || canonical.isUnknown(), canonical.isImprecise(),
				canonical.constantValue() != null, useGraph.isLive(canonical),
				useGraph.useCount(canonical), canonical.local(), interval, definingBlock, definitionIndex,
				blockFacts == null ? List.of() : blockFacts.protectedRangeProfile(),
				blockFacts == null ? List.of() : blockFacts.exceptionalProfile(),
				blockFacts == null ? List.of() : blockFacts.resourceLayerProfile());
	}

	boolean known() {
		return !unknown && !imprecise && category != IrTypeKind.UNKNOWN
				&& category != IrTypeKind.TOP && category != IrTypeKind.BOTTOM;
	}

	boolean materialized() {
		return known() && (constant || local >= 0);
	}

	boolean wide() {
		return category == IrTypeKind.LONG || category == IrTypeKind.DOUBLE;
	}

	boolean reference() {
		return category == IrTypeKind.REFERENCE || ConversionSupport.isReferenceType(value.type());
	}

	boolean compatibleWith(@NotNull ClassType expected) {
		if (ConversionSupport.isReferenceType(expected)) return reference();
		if (reference()) return false;
		if (ConversionSupport.isLongType(expected)) return category == IrTypeKind.LONG;
		if (ConversionSupport.isDoubleType(expected)) return category == IrTypeKind.DOUBLE;
		if (ConversionSupport.isFloatType(expected)) return category == IrTypeKind.FLOAT;
		return category == IrTypeKind.INT;
	}
}
