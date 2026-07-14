package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import org.jetbrains.annotations.NotNull;

/**
 * Dispatches one IR statement to the operation or effect emitter.
 */
final class IrStatementEmitter {
	private IrStatementEmitter() {}

	static void emit(@NotNull IrStmt statement, @NotNull OperationEmitter operations,
	                 @NotNull EffectEmitter effects) {
		switch (statement) {
			case IrOp op -> {
				if (op.canonical() != op) return;
				operations.emit(op);
			}
			case IrEffect effect -> effects.emit(effect);
			case IrTerminator ignored -> {}
		}
	}

	@FunctionalInterface
	interface OperationEmitter {
		void emit(@NotNull IrOp op);
	}

	@FunctionalInterface
	interface EffectEmitter {
		void emit(@NotNull IrEffect effect);
	}
}
