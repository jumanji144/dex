package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Represents a use graph for the IR, which tracks the liveness and usage of values in a method.
 */
final class LoweringUseGraph {
	private static final Object NON_STATEMENT_CONSUMER = new Object();
	private static final Object MULTIPLE_CONSUMERS = new Object();

	private final Set<IrValue> liveValues = new HashSet<>();
	private final Map<IrValue, Integer> useCounts = new HashMap<>();
	private final Map<IrValue, Object> consumers = new HashMap<>();

	private LoweringUseGraph() {
	}

	static @NotNull LoweringUseGraph analyze(@NotNull IrMethod method) {
		LoweringUseGraph graph = new LoweringUseGraph();
		graph.analyzeLiveness(method);
		graph.recordUses(method);
		return graph;
	}

	boolean isLive(@NotNull IrValue value) {
		return liveValues.contains(value.canonical());
	}

	int useCount(@NotNull IrValue value) {
		return useCounts.getOrDefault(value.canonical(), 0);
	}

	@Nullable IrStmt singleStatementConsumer(@NotNull IrValue value) {
		Object consumer = consumers.get(value.canonical());
		return consumer instanceof IrStmt statement ? statement : null;
	}

	private void analyzeLiveness(@NotNull IrMethod method) {
		ArrayDeque<IrValue> worklist = new ArrayDeque<>();
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				switch (statement) {
					case IrOp op -> {
						if (op.canonical() == op) op.inputs().forEach(input -> markLive(input, worklist));
					}
					case IrEffect effect -> effect.inputs().forEach(input -> markLive(input, worklist));
					case me.darknet.dex.convert.ir.statement.IrTerminator ignored -> {
					}
				}
			}
			if (block.terminator() != null)
				block.terminator().inputs().forEach(input -> markLive(input, worklist));
		}
		while (!worklist.isEmpty()) {
			IrValue value = worklist.removeFirst();
			if (value instanceof IrPhi phi)
				phi.operands().values().forEach(input -> markLive(input, worklist));
		}
	}

	private void recordUses(@NotNull IrMethod method) {
		for (IrBlock block : method.blocks()) {
			for (IrPhi phi : block.phis()) {
				if (phi.canonical() == phi && isLive(phi))
					phi.operands().values().forEach(this::recordNonStatementUse);
			}
			for (IrStmt statement : block.statements()) {
				switch (statement) {
					case IrOp op -> {
						if (op.canonical() == op)
							op.inputs().forEach(input -> recordStatementUse(input, statement));
					}
					case IrEffect effect -> effect.inputs().forEach(input -> recordStatementUse(input, statement));
					case me.darknet.dex.convert.ir.statement.IrTerminator ignored -> {
					}
				}
			}
			if (block.terminator() != null)
				block.terminator().inputs().forEach(input -> recordStatementUse(input, block.terminator()));
		}
	}

	private void markLive(@NotNull IrValue value, @NotNull ArrayDeque<IrValue> worklist) {
		IrValue canonical = value.canonical();
		if (liveValues.add(canonical)) worklist.addLast(canonical);
	}

	private void recordStatementUse(@NotNull IrValue value, @NotNull IrStmt consumer) {
		IrValue canonical = value.canonical();
		useCounts.merge(canonical, 1, Integer::sum);
		Object existing = consumers.get(canonical);
		if (existing == null) consumers.put(canonical, consumer);
		else if (existing != consumer) consumers.put(canonical, MULTIPLE_CONSUMERS);
	}

	private void recordNonStatementUse(@NotNull IrValue value) {
		IrValue canonical = value.canonical();
		useCounts.merge(canonical, 1, Integer::sum);
		consumers.put(canonical, NON_STATEMENT_CONSUMER);
	}
}

