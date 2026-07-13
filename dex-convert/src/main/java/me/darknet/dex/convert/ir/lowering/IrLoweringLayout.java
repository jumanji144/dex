package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;

import java.util.HashMap;
import java.util.Map;
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

	IrLoweringLayout(@NotNull IrMethod method, @NotNull Predicate<IrBlock> transparentBlock) {
		this.method = method;
		this.transparentBlock = transparentBlock;
	}

	void initializeLabels() {
		labels.clear();
		for (int i = method.blocks().size() - 1; i >= 0; i--) {
			IrBlock block = method.blocks().get(i);
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
	
	private IrBlock nextBlock(@NotNull IrBlock block) {
		int nextIndex = block.index() + 1;
		return nextIndex < method.blocks().size() ? method.blocks().get(nextIndex) : null;
	}
}

