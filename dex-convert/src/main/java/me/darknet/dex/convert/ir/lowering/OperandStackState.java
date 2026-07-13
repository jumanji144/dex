package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Small state holder for values carried between JVM stack-producing and consuming statements.
 */
final class OperandStackState {
	private final Map<IrBlock, Carry> carriesByBlock = new HashMap<>();
	private final ArrayDeque<Carry> activeCarries = new ArrayDeque<>();

	void clear() {
		activeCarries.clear();
	}

	void assign(@NotNull IrBlock block, @NotNull IrOp value, @NotNull IrStmt consumer,
	            @NotNull IrBlock consumerBlock) {
		carriesByBlock.put(block, new Carry(value, consumer, consumerBlock));
	}

	void push(@NotNull Carry carry) {
		activeCarries.addLast(carry);
	}

	boolean contains(@NotNull IrBlock block) {
		return carriesByBlock.containsKey(block);
	}

	boolean isEmpty() {
		return activeCarries.isEmpty();
	}

	Carry peekLast() {
		return activeCarries.peekLast();
	}

	void remove(@NotNull Carry carry) {
		activeCarries.remove(carry);
	}

	@NotNull ArrayDeque<Carry> activeCarries() {
		return activeCarries;
	}

	void begin(@NotNull IrBlock block) {
		Carry carry = carriesByBlock.get(block);
		if (carry != null) activeCarries.addLast(carry);
	}

	record Carry(@NotNull IrOp value, @NotNull IrStmt consumer,
	             @NotNull IrBlock consumerBlock) {}
}
