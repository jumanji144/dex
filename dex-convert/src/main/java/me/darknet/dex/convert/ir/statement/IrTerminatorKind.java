package me.darknet.dex.convert.ir.statement;

/**
 * Kinds of terminators that an IR statement may represent,
 * generally corresponding to instructions that alter control flow.
 */
public enum IrTerminatorKind {
	GOTO,
	IF,
	IF_ZERO,
	SWITCH,
	RETURN,
	THROW
}
