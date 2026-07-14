package me.darknet.dex.convert.ir.statement;

import me.darknet.dex.convert.ir.analysis.IrInstructionSemantics;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents an effect statement in the IR, which indicates a side effect that occurs during execution.
 *
 * @param kind
 * 		The kind of effect.
 * @param inputs
 * 		The input values that the effect uses.
 * @param payload
 * 		Optional additional data associated with the effect,
 * 		such as the original instruction that caused the effect.
 */

public final class IrEffect implements IrStmt {
	private final IrEffectKind kind;
	private final List<IrValue> inputs;
	private final Instruction payload;
	private final IrInstructionSemantics semantics;

	public IrEffect(@NotNull IrEffectKind kind, @NotNull List<IrValue> inputs,
	                @Nullable Instruction payload) {
		this.kind = kind;
		this.inputs = List.copyOf(inputs);
		this.payload = payload;
		this.semantics = IrInstructionSemantics.forEffect(this);
	}

	public @NotNull IrEffectKind kind() { return kind; }
	public @NotNull List<IrValue> inputs() { return inputs; }
	public @Nullable Instruction payload() { return payload; }

	public @NotNull IrInstructionSemantics semantics() {
		return semantics;
	}

	@Override
	public boolean equals(Object object) {
		return object instanceof IrEffect other && kind == other.kind
				&& inputs.equals(other.inputs) && java.util.Objects.equals(payload, other.payload);
	}

	@Override
	public int hashCode() { return java.util.Objects.hash(kind, inputs, payload); }

	@Override
	public String toString() { return kind + "(" + inputs + ", " + payload + ")"; }
}
