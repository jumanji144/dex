package me.darknet.dex.convert.ir.statement;

/**
 * Kinds of operations that an IR statement may represent,
 * generally corresponding to instructions that produce a value.
 */
public enum IrOpKind {
	ARRAY_LENGTH,
	BINARY,
	BINARY_LITERAL,
	UNARY,
	COMPARE,
	CHECK_CAST,
	INSTANCE_OF,
	NEW_INSTANCE,
	NEW_ARRAY,
	FILLED_NEW_ARRAY,
	ARRAY_GET,
	INSTANCE_GET,
	STATIC_GET,
	INVOKE,
	INVOKE_CUSTOM
}
