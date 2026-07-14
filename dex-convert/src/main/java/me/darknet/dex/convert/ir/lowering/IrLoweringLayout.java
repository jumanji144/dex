package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Owns the stable JVM labels used to lay out IR blocks.
 * <p>
 * Transparent blocks share the label of their immediate layout successor.
 * All other blocks receive a real label. Exception-specific relocation and
 * handler plans are layered on top of this map by the engine until those
 * policies are extracted into {@link IrExceptionEmitter}.
 */
final class IrLoweringLayout {
	private final IrMethod method;
	private final Predicate<IrBlock> transparentBlock;
	private final Map<IrBlock, Label> labels = new HashMap<>();
	private final Label endLabel = new Label();
	private List<IrBlock> emissionOrder;

	IrLoweringLayout(@NotNull IrMethod method, @NotNull Predicate<IrBlock> transparentBlock) {
		this.method = method;
		this.transparentBlock = transparentBlock;
		this.emissionOrder = List.copyOf(method.blocks());
	}

	void setEmissionOrder(@NotNull List<IrBlock> order) {
		if (order.size() != method.blocks().size()
				|| !sameIdentitySet(order, method.blocks()))
			throw new IllegalArgumentException("JVM layout must contain every IR block exactly once");
		emissionOrder = List.copyOf(order);
	}

	@NotNull List<IrBlock> emissionOrder() {
		return emissionOrder;
	}

	void initializeLabels() {
		labels.clear();
		for (int i = emissionOrder.size() - 1; i >= 0; i--) {
			IrBlock block = emissionOrder.get(i);
			if (!transparentBlock.test(block)) {
				labels.put(block, new Label());
				continue;
			}
			IrBlock next = nextBlock(block);
			labels.put(block, next == null ? endLabel : labels.get(next));
		}
	}

	@NotNull Map<IrBlock, Label> labels() {
		return labels;
	}

	@NotNull Label endLabel() {
		return endLabel;
	}
	
	IrBlock nextBlock(@NotNull IrBlock block) {
		int index = emissionOrder.indexOf(block) + 1;
		return index > 0 && index < emissionOrder.size() ? emissionOrder.get(index) : null;
	}

	private static boolean sameIdentitySet(List<IrBlock> first, List<IrBlock> second) {
		Set<IrBlock> identities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		identities.addAll(first);
		if (identities.size() != first.size()) return false;
		Set<IrBlock> expected = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		expected.addAll(second);
		return identities.equals(expected);
	}
}
