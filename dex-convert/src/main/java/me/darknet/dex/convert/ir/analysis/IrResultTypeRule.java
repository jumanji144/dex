package me.darknet.dex.convert.ir.analysis;

/** How an IR instruction determines its result type. */
public enum IrResultTypeRule {
	FIXED,
	BOOLEAN,
	INTEGER,
	ARRAY_ELEMENT,
	INVOKE_RETURN,
	VOID,
	UNKNOWN
}
