package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, shared lowering facts.  It is intentionally separate from IR so
 * optimization proofs can share one snapshot without mutating SSA values.
 */
final class JvmLoweringFacts {
	private final Map<IrValue, JvmValueFacts> values;
	private final Map<IrBlock, JvmBlockFacts> blocks;
	private final Map<IrBlock, List<JvmEdgeFacts>> edges;
	private final Map<IrValue, JvmLiveness.Interval> liveness;
	private final Map<IrStmt, IrBlock> statementBlocks;

	private JvmLoweringFacts(@NotNull Map<IrValue, JvmValueFacts> values,
	                        @NotNull Map<IrBlock, JvmBlockFacts> blocks,
	                        @NotNull Map<IrBlock, List<JvmEdgeFacts>> edges,
	                        @NotNull Map<IrValue, JvmLiveness.Interval> liveness,
	                        @NotNull Map<IrStmt, IrBlock> statementBlocks) {
		this.values = Collections.unmodifiableMap(new IdentityHashMap<>(values));
		this.blocks = Collections.unmodifiableMap(new IdentityHashMap<>(blocks));
		Map<IrBlock, List<JvmEdgeFacts>> edgeCopy = new IdentityHashMap<>();
		edges.forEach((block, facts) -> edgeCopy.put(block, List.copyOf(facts)));
		this.edges = Collections.unmodifiableMap(edgeCopy);
		this.liveness = Collections.unmodifiableMap(new IdentityHashMap<>(liveness));
		this.statementBlocks = Collections.unmodifiableMap(new IdentityHashMap<>(statementBlocks));
	}

	static @NotNull JvmLoweringFacts analyze(@NotNull IrMethod method,
	                                        @NotNull LoweringUseGraph useGraph) {
		Map<IrValue, JvmLiveness.Interval> liveness = JvmLiveness.analyze(method);
		Map<IrBlock, JvmBlockFacts> blocks = new IdentityHashMap<>();
		Map<IrBlock, List<JvmEdgeFacts>> edges = new IdentityHashMap<>();
		Map<IrStmt, IrBlock> statementBlocks = new IdentityHashMap<>();
		for (IrBlock block : method.blocks()) {
			blocks.put(block, JvmBlockFacts.of(block, method.exceptionRegions()));
			for (IrStmt statement : block.statements()) statementBlocks.put(statement, block);
			if (block.terminator() != null) statementBlocks.put(block.terminator(), block);
			List<JvmEdgeFacts> blockEdges = new ArrayList<>();
			for (IrBlock target : block.successors()) blockEdges.add(JvmEdgeFacts.normal(block, target));
			for (IrBlock target : block.exceptionalSuccessors()) {
				List<IrExceptionEdge> exceptional = block.exceptionEdges().stream()
						.filter(edge -> edge.handlerBlock() == target)
						.toList();
				blockEdges.add(JvmEdgeFacts.exceptional(block, target, exceptional));
			}
			edges.put(block, blockEdges);
		}

		Map<IrValue, JvmValueFacts> values = new IdentityHashMap<>();
		for (IrValue value : collectValues(method)) {
			IrValue canonical = value.canonical();
			JvmLiveness.Interval interval = liveness.get(canonical);
			if (interval == null) continue;
			IrBlock definingBlock = null;
			int definitionIndex = -1;
			if (canonical instanceof IrStmt statement) {
				definingBlock = statementBlocks.get(statement);
				if (definingBlock != null) definitionIndex = definingBlock.statements().indexOf(statement);
			}
			values.put(canonical, JvmValueFacts.of(canonical, useGraph, interval,
					definingBlock, definitionIndex,
					definingBlock == null ? null : blocks.get(definingBlock)));
		}
		return new JvmLoweringFacts(values, blocks, edges, liveness, statementBlocks);
	}

	@NotNull JvmValueFacts value(@NotNull IrValue value) {
		JvmValueFacts facts = values.get(value.canonical());
		if (facts == null) throw new IllegalArgumentException("Value is not part of lowering facts: " + value.id());
		return facts;
	}

	@Nullable JvmValueFacts findValue(@NotNull IrValue value) {
		return values.get(value.canonical());
	}

	@NotNull JvmBlockFacts block(@NotNull IrBlock block) {
		JvmBlockFacts facts = blocks.get(block);
		if (facts == null) throw new IllegalArgumentException("Block is not part of lowering facts: " + block.debugName());
		return facts;
	}

	@NotNull List<JvmEdgeFacts> edges(@NotNull IrBlock block) {
		return edges.getOrDefault(block, List.of());
	}

	@Nullable IrBlock blockOf(@NotNull IrStmt statement) {
		return statementBlocks.get(statement);
	}

	/**
	 * Aggressive expression cleanup may only re-emit a producer at a consumer
	 * inside the same resource/exception boundary.  This is deliberately a
	 * separate predicate from the existing guarded proofs so compatibility
	 * sensitive guarded output is not changed.
	 */
	boolean sameAggressiveBoundary(@NotNull IrStmt producer, @NotNull IrStmt consumer) {
		IrBlock producerBlock = blockOf(producer);
		IrBlock consumerBlock = blockOf(consumer);
		if (producerBlock == null || consumerBlock == null) return false;
		JvmBlockFacts first = block(producerBlock);
		JvmBlockFacts second = block(consumerBlock);
		boolean sameResourceLayer = first.resourceLayerProfile().equals(second.resourceLayerProfile());
		// Existing aggressive proofs already compare ordinary protected profiles.
		// Apply the stricter shared-layer rule when nested catch-all/resource
		// regions are present; this avoids changing established single-layer
		// cleanup shapes while protecting nested resource lifecycles.
		boolean nestedResourceBoundary = Math.max(first.resourceLayerProfile().size(),
				second.resourceLayerProfile().size()) > 1;
		boolean sameExceptionalProfile = first.exceptionalProfile().equals(second.exceptionalProfile())
				|| !nestedResourceBoundary;
		return (!nestedResourceBoundary || sameResourceLayer)
				&& first.protectedRangeProfile().equals(second.protectedRangeProfile())
				&& sameExceptionalProfile
				&& (producerBlock == consumerBlock ||
						(!first.handler() && !second.handler() && !first.transparent() && !second.transparent()));
	}

	@NotNull Map<IrValue, JvmLiveness.Interval> liveness() {
		return liveness;
	}

	private static List<IrValue> collectValues(@NotNull IrMethod method) {
		Map<IrValue, Boolean> seen = new IdentityHashMap<>();
		List<IrValue> values = new ArrayList<>();
		for (IrBlock block : method.blocks()) {
			add(values, seen, block.exceptionValue());
			for (IrPhi phi : block.phis()) {
				add(values, seen, phi);
				phi.operands().values().forEach(value -> add(values, seen, value));
			}
			for (IrStmt statement : block.statements()) {
				if (statement instanceof IrOp op) {
					add(values, seen, op);
					op.inputs().forEach(value -> add(values, seen, value));
				} else if (statement instanceof IrEffect effect) {
					effect.inputs().forEach(value -> add(values, seen, value));
				}
			}
			if (block.terminator() != null)
				block.terminator().inputs().forEach(value -> add(values, seen, value));
			for (IrExceptionEdge edge : block.exceptionEdges()) {
				IrValue[] state = block.exceptionalExitStates().get(edge);
				if (state != null) for (IrValue value : state) add(values, seen, value);
			}
		}
		return values;
	}

	private static void add(@NotNull List<IrValue> values, @NotNull Map<IrValue, Boolean> seen,
	                        IrValue value) {
		if (value == null) return;
		IrValue canonical = value.canonical();
		if (seen.put(canonical, Boolean.TRUE) == null) values.add(canonical);
	}
}
