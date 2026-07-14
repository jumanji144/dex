package me.darknet.dex.convert.ir.value;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;

/** Coarse, source-neutral categories used by the IR type lattice. */
public enum IrTypeKind {
	BOTTOM, INT, FLOAT, LONG, DOUBLE, REFERENCE, TOP, UNKNOWN;

	public static IrTypeKind from(ClassType type) {
		if (type == null) return UNKNOWN;
		if (ConversionSupport.isReferenceType(type)) return REFERENCE;
		if (ConversionSupport.isFloatType(type)) return FLOAT;
		if (ConversionSupport.isLongType(type)) return LONG;
		if (ConversionSupport.isDoubleType(type)) return DOUBLE;
		if (ConversionSupport.isVoidType(type)) return BOTTOM;
		return INT;
	}

	public ClassType representative() {
		return switch (this) {
			case FLOAT -> Types.FLOAT;
			case LONG -> Types.LONG;
			case DOUBLE -> Types.DOUBLE;
			case REFERENCE, TOP, UNKNOWN -> Types.OBJECT;
			case BOTTOM, INT -> Types.INT;
		};
	}

	public boolean isReference() { return this == REFERENCE; }
	public boolean isWide() { return this == LONG || this == DOUBLE; }
}
