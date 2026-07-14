package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrEffectKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.instructions.MonitorInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;

class JvmMonitorRegionPlannerTest {
	@Test
	void acceptsEquivalentNormalMonitorExitTails() {
		IrConstant lock = new IrConstant(1, Types.OBJECT, null, true);
		IrBlock entry = new IrBlock(0, 0);
		IrBlock body = new IrBlock(1, 1);
		IrBlock firstExit = exitBlock(2, lock);
		IrBlock secondExit = exitBlock(3, lock);
		entry.statements().add(monitor(lock, false));
		entry.addSuccessor(body, false);
		body.addSuccessor(firstExit, false);
		body.addSuccessor(secondExit, false);

		JvmMonitorRegionCandidate candidate = JvmMonitorRegionPlanner.discover(method(entry, body, firstExit, secondExit))
				.getFirst();

		assertTrue(candidate.proofEligible());
		assertEquals(2, candidate.exits().size());
		assertEquals(2, candidate.normalExitBlocks().size());
	}

	@Test
	void rejectsUnknownMonitorLocks() {
		IrUnknown lock = new IrUnknown(1, Types.OBJECT, IrTypeKind.REFERENCE, null, 7);
		IrBlock entry = new IrBlock(0, 0);
		IrBlock firstExit = exitBlock(1, lock);
		IrBlock secondExit = exitBlock(2, lock);
		entry.statements().add(monitor(lock, false));
		entry.addSuccessor(firstExit, false);
		entry.addSuccessor(secondExit, false);

		JvmMonitorRegionCandidate candidate = JvmMonitorRegionPlanner.discover(method(entry, firstExit, secondExit))
				.getFirst();

		assertFalse(candidate.proofEligible());
		assertTrue(candidate.proofReason().contains("reference")
				|| candidate.proofReason().contains("materialized"));
	}

	@Test
	void preservesSynchronizedMethodAccessFlag() {
		MethodMember method = new MethodMember("locked", Types.methodTypeFromDescriptor("()V"),
				ACC_PUBLIC | ACC_SYNCHRONIZED);
		assertEquals(ACC_PUBLIC | ACC_SYNCHRONIZED, method.getAccess());
	}

	private static IrEffect monitor(IrValue lock, boolean exit) {
		return new IrEffect(IrEffectKind.MONITOR, List.of(lock), new MonitorInstruction(0, exit));
	}

	private static IrBlock exitBlock(int index, IrValue lock) {
		IrBlock block = new IrBlock(index, index);
		block.statements().add(monitor(lock, true));
		block.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(), new ReturnInstruction()));
		return block;
	}

	private static IrMethod method(IrBlock... blocks) {
		return new IrMethod(new MethodMember("monitor", Types.methodTypeFromDescriptor("()V"),
				ACC_PUBLIC | ACC_STATIC), 2, List.of(blocks), blocks[0], List.of());
	}
}
