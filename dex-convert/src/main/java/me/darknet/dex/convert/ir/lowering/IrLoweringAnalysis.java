package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable facts computed before JVM bytecode emission.
 */
final class IrLoweringAnalysis {
	private final LoweringUseGraph useGraph;
	private final Set<IrOp> directReturnOperands;
	private final IrMethod method;

	private IrLoweringAnalysis(@NotNull IrMethod method, @NotNull LoweringUseGraph useGraph,
	                           @NotNull Set<IrOp> directReturnOperands) {
		this.method = method;
		this.useGraph = useGraph;
		this.directReturnOperands = Set.copyOf(directReturnOperands);
	}

	static @NotNull IrLoweringAnalysis analyze(@NotNull IrMethod method) {
		LoweringUseGraph useGraph = LoweringUseGraph.analyze(method);
		Set<IrOp> directReturnOperands = new HashSet<>();
		for (IrBlock block : method.blocks()) {
			IrTerminator terminator = block.terminator();
			if (terminator == null || terminator.kind() != IrTerminatorKind.RETURN
					|| terminator.inputs().isEmpty()) continue;
			IrValue input = terminator.inputs().getFirst().canonical();
			if (input instanceof IrPhi phi) {
				for (IrValue operand : phi.operands().values())
					addSingleUseOperation(useGraph, directReturnOperands, operand);
			} else {
				addSingleUseOperation(useGraph, directReturnOperands, input);
			}
		}
		return new IrLoweringAnalysis(method, useGraph, directReturnOperands);
	}

	@NotNull LoweringUseGraph useGraph() {
		return useGraph;
	}

	@NotNull Set<IrOp> directReturnOperands() {
		return directReturnOperands;
	}

	/**
	 * Finds zero-valued integer phis that may be seeded once in their JVM local.
	 * @param registerLocalBase First local index reserved for register values.
	 *                            Phis with locals below this index are ignored.
	 * @return Map of phis to their majority constant value, which is guaranteed to be zero.
	 */
	@NotNull Map<IrPhi, IrValue> initializedPhiValues(int registerLocalBase) {
		Map<IrPhi, IrValue> result = new HashMap<>();
		Map<Integer, IrValue> initializedLocals = new HashMap<>();
		Map<Integer, IrPhi> initializedPhisByLocal = new HashMap<>();
		for (IrBlock block : method.blocks()) {
			for (IrPhi phi : block.phis()) {
				if (phi.canonical() != phi || !useGraph.isLive(phi) || !phi.hasLocal()
						|| phi.local() < registerLocalBase || !phi.type().equals(Types.INT)) continue;
				IrValue baseline = majorityConstant(phi);
				if (baseline == null || !baseline.isZeroConstant()) continue;
				IrValue existing = initializedLocals.get(phi.local());
				if (existing != null && !sameConstant(existing, baseline)) {
					result.remove(initializedPhisByLocal.get(phi.local()));
					initializedLocals.remove(phi.local());
					initializedPhisByLocal.remove(phi.local());
					continue;
				}
				initializedLocals.put(phi.local(), baseline);
				initializedPhisByLocal.put(phi.local(), phi);
				result.put(phi, baseline);
			}
		}
		return Map.copyOf(result);
	}

	@Nullable IrValue majorityConstant(@NotNull IrPhi phi) {
		Map<Object, Integer> counts = new HashMap<>();
		Map<Object, IrValue> values = new HashMap<>();
		for (IrValue input : phi.operands().values()) {
			IrValue canonical = input.canonical();
			Object key = constantKey(canonical);
			if (key == null) return null;
			counts.merge(key, 1, Integer::sum);
			values.putIfAbsent(key, canonical);
		}
		Object bestKey = null;
		int bestCount = 1;
		for (Map.Entry<Object, Integer> entry : counts.entrySet()) {
			if (entry.getValue() > bestCount) {
				bestKey = entry.getKey();
				bestCount = entry.getValue();
			}
		}
		return bestKey == null ? null : values.get(bestKey);
	}

	boolean sameConstant(@NotNull IrValue first, @NotNull IrValue second) {
		Object firstKey = constantKey(first);
		Object secondKey = constantKey(second);
		return firstKey != null && firstKey.equals(secondKey);
	}

	private static Object constantKey(@NotNull IrValue value) {
		Object constant = value.constantValue();
		return constant != null ? constant : value.isZeroConstant() ? 0 : null;
	}

	private static void addSingleUseOperation(@NotNull LoweringUseGraph useGraph,
	                                          @NotNull Set<IrOp> directReturnOperands,
	                                          @NotNull IrValue value) {
		IrValue canonical = value.canonical();
		if (canonical instanceof IrOp op && useGraph.useCount(op) == 1)
			directReturnOperands.add(op);
	}
}

