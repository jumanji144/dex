package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coordinates recursive expression-emission decisions.
 */
final class IrExpressionPlanner {
	private static final int MAX_DECISION_DEPTH = 256;
	private final Map<DecisionKey, Boolean> cachedResults = new HashMap<>();
	private final Set<DecisionKey> activeDecisions = new HashSet<>();
	private boolean cacheResults;

	void reset(boolean cacheResults) {
		this.cacheResults = cacheResults;
		cachedResults.clear();
		activeDecisions.clear();
	}

	void invalidate() {
		cachedResults.clear();
		activeDecisions.clear();
	}

	boolean shouldSkipSeparateEmission(@NotNull List<IrStmt> statements, int index,
	                                   @Nullable IrTerminator blockTerminator,
	                                   @NotNull DecisionComputer computer) {
		DecisionKey key = new DecisionKey(statements, index, blockTerminator);
		if (cacheResults) {
			Boolean cached = cachedResults.get(key);
			if (cached != null) return cached;
		}

		// The fallback is intentionally conservative: emitting a local is safer
		// than allowing an unbounded recursive expression decision to overflow.
		if (activeDecisions.size() >= MAX_DECISION_DEPTH || !activeDecisions.add(key)) return false;
		boolean result;
		try {
			result = computer.compute(statements, index, blockTerminator);
		} finally {
			activeDecisions.remove(key);
		}
		if (cacheResults) cachedResults.put(key, result);
		return result;
	}

	@FunctionalInterface
	interface DecisionComputer {
		boolean compute(@NotNull List<IrStmt> statements, int index,
		                @Nullable IrTerminator blockTerminator);
	}

	private static final class DecisionKey {
		private final List<IrStmt> statements;
		private final int index;
		private final IrTerminator blockTerminator;

		private DecisionKey(@NotNull List<IrStmt> statements, int index,
		                    @Nullable IrTerminator blockTerminator) {
			this.statements = statements;
			this.index = index;
			this.blockTerminator = blockTerminator;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof DecisionKey key)) return false;
			return statements == key.statements && index == key.index && blockTerminator == key.blockTerminator;
		}

		@Override
		public int hashCode() {
			int result = System.identityHashCode(statements);
			result = 31 * result + index;
			return 31 * result + System.identityHashCode(blockTerminator);
		}
	}
}

