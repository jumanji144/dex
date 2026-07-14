package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrOpKind;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

class JvmSingleUsePlannerTest {
	@Test
	void discoversAProvenDirectReturnCandidate() {
		IrBlock block = new IrBlock(0, 0);
		IrOp operation = addOperation(block, 1);
		block.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(operation),
				new ReturnInstruction(0, Return.NORMAL)));
		IrMethod method = method(block);
		LoweringUseGraph graph = LoweringUseGraph.analyze(method);
		JvmOptimizationGuards guards = new JvmOptimizationGuards(method, graph);

		List<JvmSingleUseCandidate> candidates = JvmSingleUsePlanner.discover(method, graph, guards);
		JvmSingleUseCandidate candidate = candidates.stream().filter(value ->
				value.mode() == JvmSingleUseCandidate.Mode.DIRECT_RETURN).findFirst().orElseThrow();
		assertTrue(candidate.proofEligible());
		assertEquals(0, candidate.consumerInputIndex());
	}

	@Test
	void rejectsAProducerSeparatedFromItsConsumerByAnObservableStatement() {
		IrBlock block = new IrBlock(0, 0);
		IrOp producer = addOperation(block, 1);
		addOperation(block, 2);
		block.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(producer),
				new ReturnInstruction(0, Return.NORMAL)));
		IrMethod method = method(block);
		LoweringUseGraph graph = LoweringUseGraph.analyze(method);
		JvmOptimizationGuards guards = new JvmOptimizationGuards(method, graph);

		JvmSingleUseCandidate candidate = JvmSingleUsePlanner.discover(method, graph, guards).stream()
				.filter(value -> value.producer() == producer).findFirst().orElseThrow();
		assertFalse(candidate.proofEligible());
		assertTrue(candidate.proofReason().contains("adjacent"));
	}

	private static IrOp addOperation(IrBlock block, int id) {
		IrOp operation = new IrOp(id, Types.INT, IrOpKind.BINARY,
				List.of(new IrConstant(id + 10, Types.INT, 1, false),
						new IrConstant(id + 20, Types.INT, 2, false)),
				new BinaryInstruction(me.darknet.dex.file.instructions.Opcodes.ADD_INT, 0, 0, 0));
		operation.local(id);
		block.statements().add(operation);
		return operation;
	}

	private static IrMethod method(IrBlock block) {
		return new IrMethod(new MethodMember("singleUse", Types.methodTypeFromDescriptor("()I"),
				ACC_PUBLIC | ACC_STATIC), 3, List.of(block), block, List.of());
	}
}
