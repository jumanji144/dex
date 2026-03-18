package me.darknet.dex.convert.ir;

import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.value.IrExceptionValue;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a basic block in the intermediate representation of a method.
 */
public class IrBlock {
	private final int index;
	private final int startOffset;
	private final List<DexInstructionNode> dexInstructions = new ArrayList<>();
	private final List<IrBlock> predecessors = new ArrayList<>();
	private final List<IrBlock> successors = new ArrayList<>();
	private final List<IrBlock> exceptionalSuccessors = new ArrayList<>();
	private final List<IrPhi> phis = new ArrayList<>();
	private final List<IrStmt> statements = new ArrayList<>();
	private final Map<IrBlock, IrValue> exceptionInputs = new HashMap<>();
	private IrTerminator terminator;
	private IrValue[] exitState;
	private IrValue[] exceptionalExitState;
	private IrExceptionValue exceptionValue;

	public IrBlock(int index, int startOffset) {
		this.index = index;
		this.startOffset = startOffset;
	}

	public int index() {
		return index;
	}

	public int startOffset() {
		return startOffset;
	}

	public @NotNull String debugName() {
		return "b" + index;
	}

	public @NotNull List<DexInstructionNode> dexInstructions() {
		return dexInstructions;
	}

	public @NotNull List<IrBlock> predecessors() {
		return predecessors;
	}

	public @NotNull List<IrBlock> successors() {
		return successors;
	}

	public @NotNull List<IrBlock> exceptionalSuccessors() {
		return exceptionalSuccessors;
	}

	public @NotNull List<IrPhi> phis() {
		return phis;
	}

	public @NotNull List<IrStmt> statements() {
		return statements;
	}

	public @Nullable IrTerminator terminator() {
		return terminator;
	}

	public void terminator(@NotNull IrTerminator terminator) {
		this.terminator = terminator;
	}

	public @Nullable IrValue[] exitState() {
		return exitState;
	}

	public void exitState(@NotNull IrValue[] exitState) {
		this.exitState = exitState;
	}

	public @Nullable IrValue[] exceptionalExitState() {
		return exceptionalExitState;
	}

	public void exceptionalExitState(@NotNull IrValue[] exceptionalExitState) {
		this.exceptionalExitState = exceptionalExitState;
	}

	public @Nullable IrExceptionValue exceptionValue() {
		return exceptionValue;
	}

	public void addPredecessor(@NotNull IrBlock predecessor) {
		if (!predecessors.contains(predecessor)) predecessors.add(predecessor);
	}

	public void addSuccessor(@NotNull IrBlock successor, boolean exceptional) {
		if (exceptional) {
			if (!exceptionalSuccessors.contains(successor)) exceptionalSuccessors.add(successor);
		} else if (!successors.contains(successor)) {
			successors.add(successor);
		}
		successor.addPredecessor(this);
	}

	public void addExceptionInput(@NotNull IrBlock predecessor, @NotNull IrValue value) {
		exceptionInputs.put(predecessor, value);
	}

	public @NotNull Map<IrBlock, IrValue> exceptionInputs() {
		return exceptionInputs;
	}

	public IrExceptionValue ensureExceptionValue(int id, @Nullable ClassType type) {
		if (exceptionValue == null) {
			exceptionValue = new IrExceptionValue(id, type == null ? Types.instanceType(Throwable.class) : type);
		}
		return exceptionValue;
	}
}
