package me.darknet.dex.convert.ir.statement;

/**
 * Kinds of side effects that an IR statement may have,
 * generally corresponding to instructions that write to memory or have other side effects.
 */
public enum IrEffectKind {
	ARRAY_PUT,
	INSTANCE_PUT,
	STATIC_PUT,
	FILL_ARRAY_DATA,
	MONITOR
}
