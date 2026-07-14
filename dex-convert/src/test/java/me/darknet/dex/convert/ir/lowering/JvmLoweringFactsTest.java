package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrOpKind;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrValue;
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

class JvmLoweringFactsTest {
	@Test
	void factsShareValueCategoryUseAndMaterializationState() {
		IrBlock block = new IrBlock(0, 0);
		IrOp operation = new IrOp(1, Types.INT, IrOpKind.BINARY,
				List.of(new IrConstant(2, Types.INT, 1, false),
						new IrConstant(3, Types.INT, 2, false)),
				new BinaryInstruction(me.darknet.dex.file.instructions.Opcodes.ADD_INT, 0, 0, 0));
		operation.local(1);
		block.statements().add(operation);
		block.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(operation),
				new ReturnInstruction(0, Return.NORMAL)));
		IrMethod method = new IrMethod(new MethodMember("facts", Types.methodTypeFromDescriptor("()I"),
				ACC_PUBLIC | ACC_STATIC), 4, List.of(block), block, List.of());

		LoweringUseGraph useGraph = LoweringUseGraph.analyze(method);
		JvmLoweringFacts facts = JvmLoweringFacts.analyze(method, useGraph);
		JvmValueFacts value = facts.value(operation);

		assertEquals(me.darknet.dex.convert.ir.value.IrTypeKind.INT, value.category());
		assertTrue(value.known());
		assertTrue(value.materialized());
		assertEquals(1, value.useCount());
		assertTrue(value.live());
		assertEquals(1, value.local());
		assertFalse(facts.block(block).handler());
		assertFalse(facts.block(block).protectedBlock());
	}

	@Test
	void factsRepresentNormalAndExceptionalEdgesSeparately() {
		IrBlock source = new IrBlock(0, 0);
		IrBlock target = new IrBlock(1, 1);
		source.addSuccessor(target, false);
		source.terminator(new IrTerminator(IrTerminatorKind.GOTO, List.of(),
				new me.darknet.dex.tree.definitions.instructions.GotoInstruction(
						new me.darknet.dex.tree.definitions.instructions.Label(1, 1))));
		target.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(),
				new ReturnInstruction(0, Return.VOID)));
		IrMethod method = new IrMethod(new MethodMember("edges", Types.methodTypeFromDescriptor("()V"),
				ACC_PUBLIC | ACC_STATIC), 1, List.of(source, target), source, List.of());

		JvmLoweringFacts facts = JvmLoweringFacts.analyze(method, LoweringUseGraph.analyze(method));
		assertEquals(1, facts.edges(source).size());
		JvmEdgeFacts edge = facts.edges(source).getFirst();
		assertFalse(edge.exceptional());
		assertEquals(target, edge.target());
		assertTrue(facts.block(source).transparent());
	}
}
