package me.darknet.dex.convert.ir.statement;

/**
 * A statement in the IR, which may be an operation, an effect, or a terminator.
 */
public sealed interface IrStmt permits IrOp, IrEffect, IrTerminator {}
