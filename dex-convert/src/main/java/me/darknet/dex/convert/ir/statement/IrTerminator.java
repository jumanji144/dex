package me.darknet.dex.convert.ir.statement;

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
public record IrTerminator(@NotNull IrTerminatorKind kind, @NotNull List<IrValue> inputs,
                           @Nullable Instruction payload) implements IrStmt {}
