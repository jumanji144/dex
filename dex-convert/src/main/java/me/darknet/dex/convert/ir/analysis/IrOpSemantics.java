package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import org.jetbrains.annotations.NotNull;

/**
 * Provides semantic analysis for IR operations, determining their effects and whether they can be removed.
 */
public final class IrOpSemantics {
	public enum Effect {
		PURE,
		MAY_THROW,
		OBSERVABLE
	}

	private IrOpSemantics() {
	}

	public static @NotNull Effect effect(@NotNull IrOp op) {
		return switch (op.kind()) {
			case BINARY, BINARY_LITERAL, UNARY, COMPARE, INSTANCE_OF ->
					mayThrow(op) ? Effect.MAY_THROW : Effect.PURE;
			case ARRAY_LENGTH, CHECK_CAST, NEW_INSTANCE, NEW_ARRAY, FILLED_NEW_ARRAY -> Effect.MAY_THROW;
			case ARRAY_GET, INSTANCE_GET, STATIC_GET, INVOKE, INVOKE_CUSTOM -> Effect.OBSERVABLE;
		};
	}

	public static boolean isRemovable(@NotNull IrOp op) {
		return effect(op) == Effect.PURE;
	}

	public static boolean mayThrow(@NotNull IrOp op) {
		return op.payload() instanceof Instruction instruction && InstructionSemantics.canThrow(instruction);
	}
}
