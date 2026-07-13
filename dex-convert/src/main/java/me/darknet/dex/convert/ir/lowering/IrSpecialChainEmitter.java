package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrStmt;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Orders the optional expression-chain recognizers used during block emission.
 * <p>
 * Recognizers return their consumed statement count.  Keeping this
 * precedence in one place prevents a newly added peephole from accidentally
 * changing the ordering between constructor/field and array initialization
 * chains.
 */
final class IrSpecialChainEmitter {
	int emit(@NotNull List<IrStmt> statements, int index, @NotNull Host host) {
		if (host.tryEmitConstructAndPutChain(statements, index)) return 2;
		if (host.tryEmitConstructAndPutChainAcrossBlocks(statements, index)) return 1;
		return host.tryEmitArrayStaticPutChain(statements, index);
	}

	interface Host {
		boolean tryEmitConstructAndPutChain(@NotNull List<IrStmt> statements, int index);

		boolean tryEmitConstructAndPutChainAcrossBlocks(@NotNull List<IrStmt> statements, int index);

		int tryEmitArrayStaticPutChain(@NotNull List<IrStmt> statements, int index);
	}
}

