package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Immutable normal/exceptional edge facts used by lowering proofs. */
record JvmEdgeFacts(
		@NotNull IrBlock source,
		@NotNull IrBlock target,
		boolean exceptional,
		@NotNull List<IrExceptionEdge> exceptionEdges,
		@NotNull Map<IrPhi, IrValue> phiInputs,
		IrValue @NotNull [] exceptionalState) {
	static @NotNull JvmEdgeFacts normal(@NotNull IrBlock source, @NotNull IrBlock target) {
		Map<IrPhi, IrValue> phiInputs = new IdentityHashMap<>();
		for (IrPhi phi : target.phis()) {
			IrValue input = phi.operands().get(source);
			if (input != null) phiInputs.put(phi, input.canonical());
		}
		return new JvmEdgeFacts(source, target, false, List.of(), Map.copyOf(phiInputs), new IrValue[0]);
	}

	static @NotNull JvmEdgeFacts exceptional(@NotNull IrBlock source, @NotNull IrBlock target,
	                                        @NotNull List<IrExceptionEdge> edges) {
		IrValue[] state = edges.isEmpty() ? new IrValue[0]
				: copyState(source.exceptionalExitStates().get(edges.getFirst()));
		return new JvmEdgeFacts(source, target, true, List.copyOf(edges), Map.of(), state);
	}

	private static IrValue[] copyState(IrValue[] state) {
		if (state == null) return new IrValue[0];
		IrValue[] copy = state.clone();
		for (int i = 0; i < copy.length; i++)
			if (copy[i] != null) copy[i] = copy[i].canonical();
		return copy;
	}
}
