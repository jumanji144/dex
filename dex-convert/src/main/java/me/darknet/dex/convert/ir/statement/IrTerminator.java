package me.darknet.dex.convert.ir.statement;

import me.darknet.dex.convert.ir.analysis.IrInstructionSemantics;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a terminator statement in the IR, which indicates the end of a basic block and defines control flow.
 *
 * @param kind
 * 		The kind of terminator.
 * @param inputs
 * 		The input values that the terminator uses.
 * @param payload
 * 		Optional additional data associated with the terminator.
 */
public final class IrTerminator implements IrStmt {
	private final IrTerminatorKind kind;
	private final List<IrValue> inputs;
	private final Instruction payload;
	private final IrInstructionSemantics semantics;

	public IrTerminator(@NotNull IrTerminatorKind kind, @NotNull List<IrValue> inputs,
	                    @Nullable Instruction payload) {
		this.kind = kind;
		this.inputs = List.copyOf(inputs);
		this.payload = payload;
		this.semantics = IrInstructionSemantics.forTerminator(this);
	}

	public @NotNull IrTerminatorKind kind() { return kind; }
	public @NotNull List<IrValue> inputs() { return inputs; }
	public @Nullable Instruction payload() { return payload; }
	public @NotNull IrInstructionSemantics semantics() { return semantics; }

	@Override
	public boolean equals(Object object) {
		return object instanceof IrTerminator other && kind == other.kind
				&& inputs.equals(other.inputs) && java.util.Objects.equals(payload, other.payload);
	}

	@Override
	public int hashCode() { return java.util.Objects.hash(kind, inputs, payload); }

	@Override
	public String toString() { return kind + "(" + inputs + ", " + payload + ")"; }
}
