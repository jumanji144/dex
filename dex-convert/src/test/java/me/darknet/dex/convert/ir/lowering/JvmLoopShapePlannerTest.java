package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrOpKind;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

class JvmLoopShapePlannerTest {
	@Test
	void discoversCountedLoopAndPreservesInductionProof() {
		IrBlock entry = new IrBlock(0, 0);
		IrBlock header = new IrBlock(1, 1);
		IrBlock body = new IrBlock(2, 2);
		IrBlock exit = new IrBlock(3, 3);
		IrPhi index = new IrPhi(10, header, 0, Types.INT);
		index.local(1);
		IrConstant initial = new IrConstant(11, Types.INT, 0, false);
		IrOp increment = new IrOp(12, Types.INT, IrOpKind.BINARY_LITERAL, List.of(index),
				new BinaryLiteralInstruction(Opcodes.ADD_INT_LIT8, 0, 0, 1));
		increment.local(1);
		index.putOperand(entry, initial);
		index.putOperand(body, increment);
		header.phis().add(index);
		header.terminator(new IrTerminator(IrTerminatorKind.IF,
				List.of(index, new IrConstant(13, Types.INT, 10, false)),
				new BranchInstruction(BranchInstruction.TEST_IF_GE, 0, 1, new Label(3, 3))));
		body.statements().add(increment);
		body.terminator(new IrTerminator(IrTerminatorKind.GOTO, List.of(), new GotoInstruction(new Label(1, 1))));
		exit.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(), new ReturnInstruction()));
		entry.addSuccessor(header, false);
		header.addSuccessor(exit, false);
		header.addSuccessor(body, false);
		body.addSuccessor(header, false);

		IrMethod method = method(entry, header, body, exit);
		JvmOptimizationGuards guards = new JvmOptimizationGuards(method, LoweringUseGraph.analyze(method));
		JvmLoopShapeCandidate candidate = JvmLoopShapePlanner.discover(method, guards).stream()
				.filter(value -> value.kind() == JvmLoopShapeKind.COUNTED).findFirst().orElseThrow();

		assertTrue(candidate.proofEligible(), candidate.proofReason());
		assertEquals(header, candidate.header());
		assertEquals(body, candidate.backedge());
		assertEquals(List.of(index), candidate.phis());
	}

	@Test
	void discoversShortCircuitPredicateChain() {
		IrBlock entry = new IrBlock(0, 0);
		IrBlock header = new IrBlock(1, 1);
		IrBlock predicate = new IrBlock(2, 2);
		IrBlock body = new IrBlock(3, 3);
		IrBlock exit = new IrBlock(4, 4);
		header.terminator(new IrTerminator(IrTerminatorKind.IF_ZERO,
				List.of(new IrConstant(1, Types.INT, 1, false)),
				new me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction(0, 0, new Label(4, 4))));
		predicate.terminator(new IrTerminator(IrTerminatorKind.IF_ZERO,
				List.of(new IrConstant(2, Types.INT, 1, false)),
				new me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction(0, 0, new Label(4, 4))));
		body.terminator(new IrTerminator(IrTerminatorKind.GOTO, List.of(), new GotoInstruction(new Label(1, 1))));
		exit.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(), new ReturnInstruction()));
		entry.addSuccessor(header, false);
		header.addSuccessor(exit, false);
		header.addSuccessor(predicate, false);
		predicate.addSuccessor(exit, false);
		predicate.addSuccessor(body, false);
		body.addSuccessor(header, false);

		IrMethod method = method(entry, header, predicate, body, exit);
		JvmOptimizationGuards guards = new JvmOptimizationGuards(method, LoweringUseGraph.analyze(method));
		JvmLoopShapeCandidate candidate = JvmLoopShapePlanner.discover(method, guards).stream()
				.filter(value -> value.kind() == JvmLoopShapeKind.SHORT_CIRCUIT).findFirst().orElseThrow();
		assertTrue(candidate.proofEligible(), candidate.proofReason());
		assertEquals(exit, candidate.canonicalExit());
	}

	@Test
	void policyEnablesLoopRestructuringOnlyForAggressiveMode() {
		assertFalse(JvmLoweringPolicy.DETERMINISTIC_LOCAL.loopRestructuring());
		assertFalse(JvmLoweringPolicy.GUARDED_OPTIMIZED.loopRestructuring());
		assertTrue(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED.loopRestructuring());
	}

	@Test
	void layoutRejectsMissingOrDuplicateBlocks() {
		IrBlock entry = new IrBlock(0, 0);
		IrMethod method = method(entry);
		IrLoweringLayout layout = new IrLoweringLayout(method, ignored -> false);
		assertThrows(IllegalArgumentException.class, () -> layout.setEmissionOrder(List.of()));
		assertThrows(IllegalArgumentException.class, () -> layout.setEmissionOrder(List.of(entry, entry)));
	}

	private static IrMethod method(IrBlock... blocks) {
		return new IrMethod(new MethodMember("loop", Types.methodTypeFromDescriptor("()V"),
				ACC_PUBLIC | ACC_STATIC), 4, List.of(blocks), blocks[0], List.of());
	}
}
