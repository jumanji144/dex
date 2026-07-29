package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Value intervals used by JVM materialization and future target allocators. */
final class JvmLiveness {
	record Interval(@NotNull IrValue value, int start, int end) {}

	private JvmLiveness() {}

	static @NotNull Map<IrValue, Interval> analyze(@NotNull IrMethod method) {
		Map<IrValue, int[]> ranges = new IdentityHashMap<>();
		Map<IrBlock, Integer> blockStarts = new IdentityHashMap<>();
		int blockPosition = 0;
		for (IrBlock block : method.blocks()) {
			blockStarts.put(block, blockPosition);
			blockPosition += block.statements().size() + (block.terminator() == null ? 0 : 1);
		}
		int position = 0;
		for (IrBlock block : method.blocks()) {
			if (block.exceptionValue() != null) touch(ranges, block.exceptionValue().canonical(), position);
			for (IrPhi phi : block.phis()) {
				touch(ranges, phi, position);
				for (IrValue input : phi.operands().values()) touch(ranges, input.canonical(), position);
			}
			for (IrStmt statement : block.statements()) {
				if (statement instanceof IrOp op) for (IrValue input : op.inputs()) touch(ranges, input.canonical(), position);
				if (statement instanceof IrEffect effect) for (IrValue input : effect.inputs()) touch(ranges, input.canonical(), position);
				if (statement instanceof IrValue value) touch(ranges, value.canonical(), position);
				position++;
			}
			if (block.terminator() != null) {
				for (IrValue input : block.terminator().inputs()) touch(ranges, input.canonical(), position);
				position++;
			}
			// An exceptional edge is a separate consumer boundary even when its
			// handler has no explicit phi yet.
			for (IrExceptionEdge edge : block.exceptionEdges()) {
				IrValue[] state = block.exceptionalExitStates().get(edge);
				if (state != null) {
					// The exceptional state is consumed at handler entry, not at
					// the throwing block's normal position.  Extending the interval
					// to that entry prevents a slot from being reused for a value
					// that the handler can still observe.
					int handlerPosition = blockStarts.getOrDefault(edge.handlerBlock(), position);
					for (IrValue value : state)
						if (value != null) touch(ranges, value.canonical(), handlerPosition);
				}
			}
		}
		Map<IrValue, Interval> result = new IdentityHashMap<>();
		for (Map.Entry<IrValue, int[]> entry : ranges.entrySet())
			result.put(entry.getKey(), new Interval(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
		return result;
	}

	private static void touch(Map<IrValue, int[]> ranges, IrValue value, int position) {
		if (value == null) return;
		int[] range = ranges.computeIfAbsent(value, ignored -> new int[] {position, position});
		range[0] = Math.min(range[0], position);
		range[1] = Math.max(range[1], position);
	}
}
