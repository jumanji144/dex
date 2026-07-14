package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** An immutable lowering-time description of one possible cleanup suffix. */
record JvmCleanupTailCandidate(
		@NotNull IrBlock entry,
		@NotNull IrBlock terminalBlock,
		@NotNull List<IrBlock> tailBlocks,
		@NotNull List<IrStmt> statements,
		@NotNull List<IrBlock> incomingEdges,
		@NotNull List<IrExceptionEdge> exceptionalEdges,
		@NotNull List<IrValue> requiredValues,
		int sourceOffset,
		@NotNull List<String> exceptionRangeProfile,
		@NotNull JvmCleanupTailSignature signature) {
	JvmCleanupTailCandidate {
		tailBlocks = List.copyOf(tailBlocks);
		statements = List.copyOf(statements);
		incomingEdges = List.copyOf(incomingEdges);
		exceptionalEdges = List.copyOf(exceptionalEdges);
		requiredValues = List.copyOf(requiredValues);
		exceptionRangeProfile = List.copyOf(exceptionRangeProfile);
	}

	boolean terminal() {
		return terminalBlock.terminator() != null;
	}
}
