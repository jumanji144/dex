package me.darknet.dex.convert.ir;

import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.convert.ir.analysis.IrInstructionSemantics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Instruction-specific exceptional transfer metadata. */

public final class IrExceptionEdge {
	private final IrBlock sourceBlock;
	private final Instruction throwingInstruction;
	private final IrBlock handlerBlock;
	private final Handler handler;
	private final int sourceOffset;
	private final IrInstructionSemantics semantics;

	public IrExceptionEdge(@NotNull IrBlock sourceBlock, @NotNull Instruction throwingInstruction,
	                       @NotNull IrBlock handlerBlock, @Nullable Handler handler, int sourceOffset) {
		this.sourceBlock = sourceBlock;
		this.throwingInstruction = throwingInstruction;
		this.handlerBlock = handlerBlock;
		this.handler = handler;
		this.sourceOffset = sourceOffset;
		this.semantics = IrInstructionSemantics.forThrowingInstruction(throwingInstruction);
	}

	public @NotNull IrBlock sourceBlock() { return sourceBlock; }
	public @NotNull Instruction throwingInstruction() { return throwingInstruction; }
	public @NotNull IrBlock handlerBlock() { return handlerBlock; }
	public @Nullable Handler handler() { return handler; }
	public int sourceOffset() { return sourceOffset; }

	public int throwMask() {
		return semantics.throwMask();
	}

	public @NotNull IrInstructionSemantics semantics() {
		return semantics;
	}

	@Override
	public boolean equals(Object object) {
		return object instanceof IrExceptionEdge other && sourceBlock == other.sourceBlock
				&& throwingInstruction.equals(other.throwingInstruction) && handlerBlock == other.handlerBlock
				&& java.util.Objects.equals(handler, other.handler) && sourceOffset == other.sourceOffset;
	}

	@Override
	public int hashCode() { return java.util.Objects.hash(sourceBlock, throwingInstruction, handlerBlock, handler, sourceOffset); }
}
