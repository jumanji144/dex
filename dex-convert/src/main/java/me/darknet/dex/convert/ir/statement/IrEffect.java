package me.darknet.dex.convert.ir.statement;

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
public record IrEffect(@NotNull IrEffectKind kind, @NotNull List<IrValue> inputs,
                       @Nullable Instruction payload) implements IrStmt {}
