package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Emits the shared body of a normal or deferred IR block.
 */
final class IrBlockEmitter {
	void emitBody(@NotNull IrBlock block, @NotNull Host host) {
		host.beginOperandStackCarry(block);
		List<IrStmt> statements = block.statements();
		for (int i = 0; i < statements.size(); i++) {
			IrStmt statement = statements.get(i);
			if (statement instanceof IrEffect effect && host.wasEffectEmitted(effect)) continue;
			if (host.tryCarryInvokeInput(block, statements, i, block.terminator())) continue;
			if (host.isDirectPhiReturnOperand(block, statement)) continue;
			if (host.shouldSkipSeparateEmission(statements, i, block.terminator())) continue;
			int chainLength = host.emitSpecialChain(statements, i);
			if (chainLength > 0) {
				i += chainLength - 1;
				continue;
			}
			host.setCurrentStatement(statement);
			host.emitStatement(statement);
			if (statement instanceof IrOp op && op.canonical() == op)
				host.markOperationEmitted(op);
			host.setCurrentStatement(null);
		}
		host.beforeTerminator(block);
		host.setCurrentStatement(block.terminator());
		host.emitTerminator(block);
		host.setCurrentStatement(null);
		if (host.hasUnconsumedOperandStackCarry(block))
			throw new IllegalStateException("Operand-stack value was not consumed in " + block.debugName());
		host.clearOperandStackCarry();
	}

	interface Host {
		void beginOperandStackCarry(@NotNull IrBlock block);

		boolean wasEffectEmitted(@NotNull IrEffect effect);

		boolean tryCarryInvokeInput(@NotNull IrBlock block, @NotNull List<IrStmt> statements, int index,
		                            IrTerminator blockTerminator);

		boolean isDirectPhiReturnOperand(@NotNull IrBlock block, @NotNull IrStmt statement);

		boolean shouldSkipSeparateEmission(@NotNull List<IrStmt> statements, int index,
		                                  IrTerminator blockTerminator);

		int emitSpecialChain(@NotNull List<IrStmt> statements, int index);

		void setCurrentStatement(IrStmt statement);

		void emitStatement(@NotNull IrStmt statement);

		void beforeTerminator(@NotNull IrBlock block);

		void markOperationEmitted(@NotNull IrOp op);

		void emitTerminator(@NotNull IrBlock block);

		boolean hasUnconsumedOperandStackCarry(@NotNull IrBlock block);

		void clearOperandStackCarry();
	}
}
