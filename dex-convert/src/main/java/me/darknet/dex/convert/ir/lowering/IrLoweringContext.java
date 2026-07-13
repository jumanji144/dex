package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable environment shared by lowering collaborators.
 */
final class IrLoweringContext {
	private final IrMethod method;
	private final MethodVisitor methodVisitor;
	private final Map<Integer, IrBlock> blockByOffset;

	private IrLoweringContext(@NotNull IrMethod method, @NotNull MethodVisitor methodVisitor) {
		this.method = method;
		this.methodVisitor = methodVisitor;
		Map<Integer, IrBlock> blocks = new HashMap<>();
		for (IrBlock block : method.blocks()) blocks.put(block.startOffset(), block);
		this.blockByOffset = Map.copyOf(blocks);
	}

	static @NotNull IrLoweringContext create(@NotNull IrMethod method,
	                                         @NotNull MethodVisitor methodVisitor) {
		return new IrLoweringContext(method, methodVisitor);
	}

	@NotNull IrMethod method() {
		return method;
	}

	@NotNull MethodVisitor methodVisitor() {
		return methodVisitor;
	}

	@NotNull Map<Integer, IrBlock> blockByOffset() {
		return blockByOffset;
	}
}

