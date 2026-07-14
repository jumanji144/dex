package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.analysis.IrInstructionSemantics;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.value.IrType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Immutable proof input for one aggressive producer/consumer elimination. */
record JvmSingleUseCandidate(
		@NotNull IrOp producer,
		@NotNull List<IrOp> operations,
		@Nullable IrStmt consumer,
		@NotNull IrBlock producerBlock,
		@NotNull IrBlock consumerBlock,
		@NotNull List<IrBlock> glueBlocks,
		int consumerInputIndex,
	@NotNull Mode mode,
		int useCount,
		boolean live,
		@NotNull IrInstructionSemantics producerSemantics,
		@Nullable IrInstructionSemantics consumerSemantics,
		@NotNull IrType resultType,
		@NotNull List<IrExceptionEdge> exceptionalEdges,
		@NotNull List<String> protectedRangeProfile,
		int sourceOffset,
		boolean proofEligible,
		@NotNull String proofReason) {
	enum Mode {
		RECEIVER_CHAIN,
		EXPRESSION_SLICE,
		DIRECT_ARGUMENT,
		DIRECT_RETURN,
		CONSTRUCTOR_TO_THROW,
		CONSTRUCTOR_CHAIN,
		DEAD_CONSTRUCTION
	}

	JvmSingleUseCandidate {
		operations = List.copyOf(operations);
		glueBlocks = List.copyOf(glueBlocks);
		exceptionalEdges = List.copyOf(exceptionalEdges);
		protectedRangeProfile = List.copyOf(protectedRangeProfile);
	}
}
