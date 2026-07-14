package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
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
	record UseSite(IrBlock block, IrStmt consumer, int inputIndex, boolean phi) {}
	private static final Object NON_STATEMENT_CONSUMER = new Object();
	private static final Object MULTIPLE_CONSUMERS = new Object();

	private final Set<IrValue> liveValues = new HashSet<>();
	private final Map<IrValue, Integer> useCounts = new HashMap<>();
	private final Map<IrValue, Object> consumers = new HashMap<>();
	private final Map<IrValue, java.util.List<UseSite>> useSites = new HashMap<>();

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

	@NotNull java.util.List<UseSite> useSites(@NotNull IrValue value) {
		return java.util.List.copyOf(useSites.getOrDefault(value.canonical(), java.util.List.of()));
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
					phi.operands().forEach((predecessor, input) -> recordNonStatementUse(input,
							new UseSite(block, null, -1, true)));
			}
			for (int statementIndex = 0; statementIndex < block.statements().size(); statementIndex++) {
				IrStmt statement = block.statements().get(statementIndex);
				switch (statement) {
					case IrOp op -> {
						if (op.canonical() == op)
							for (int inputIndex = 0; inputIndex < op.inputs().size(); inputIndex++)
								recordStatementUse(op.inputs().get(inputIndex), statement,
										new UseSite(block, statement, inputIndex, false));
					}
					case IrEffect effect -> {
						for (int inputIndex = 0; inputIndex < effect.inputs().size(); inputIndex++)
							recordStatementUse(effect.inputs().get(inputIndex), statement,
									new UseSite(block, statement, inputIndex, false));
					}
					case me.darknet.dex.convert.ir.statement.IrTerminator ignored -> {
					}
				}
			}
			if (block.terminator() != null) {
				IrTerminator terminator = block.terminator();
				for (int inputIndex = 0; inputIndex < terminator.inputs().size(); inputIndex++)
					recordStatementUse(terminator.inputs().get(inputIndex), terminator,
						new UseSite(block, terminator, inputIndex, false));
			}
		}
	}

	private void markLive(@NotNull IrValue value, @NotNull ArrayDeque<IrValue> worklist) {
		IrValue canonical = value.canonical();
		if (liveValues.add(canonical)) worklist.addLast(canonical);
	}

	private void recordStatementUse(@NotNull IrValue value, @NotNull IrStmt consumer,
	                                @NotNull UseSite useSite) {
		IrValue canonical = value.canonical();
		useCounts.merge(canonical, 1, Integer::sum);
		useSites.computeIfAbsent(canonical, ignored -> new java.util.ArrayList<>()).add(useSite);
		Object existing = consumers.get(canonical);
		if (existing == null) consumers.put(canonical, consumer);
		else if (existing != consumer) consumers.put(canonical, MULTIPLE_CONSUMERS);
	}

	private void recordNonStatementUse(@NotNull IrValue value, @NotNull UseSite useSite) {
		IrValue canonical = value.canonical();
		useCounts.merge(canonical, 1, Integer::sum);
		useSites.computeIfAbsent(canonical, ignored -> new java.util.ArrayList<>()).add(useSite);
		consumers.put(canonical, NON_STATEMENT_CONSUMER);
	}
}
