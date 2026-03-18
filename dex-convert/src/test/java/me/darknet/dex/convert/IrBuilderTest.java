package me.darknet.dex.convert;

import me.darknet.dex.convert.ir.build.IrBuilder;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.util.IrTestUtils;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveResultInstruction;
import me.darknet.dex.tree.definitions.instructions.Result;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for direct usage of {@link IrBuilder}.
 */
class IrBuilderTest implements Opcodes {
	@Test
	void buildsPhiForIfMerge() {
		// Rough pseudocode for the method being tested:
		// int branch(int x) {
		//     if (x == 0) {
		//         return 2;
		//     } else {
		//         return 1;
		//     }
		// }
		Label elseLabel = new Label();
		Label endLabel = new Label();
		MethodMember method = new MethodMember("branch", Types.methodTypeFromDescriptor("(I)I"),
				org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC);
		method.setCode(IrTestUtils.code(3, 1,
				new BranchInstruction(BranchInstruction.TEST_IF_EQ, 2, 2, elseLabel),
				new ConstInstruction(0, 2),
				new GotoInstruction(endLabel),
				elseLabel,
				new ConstInstruction(0, 1),
				endLabel,
				new ReturnInstruction(0)));

		// The merge block at the end of the method should contain a phi function for register 0 that
		// merges the values from the true and false branches (2 and 1 respectively).
		IrMethod ir = new IrBuilder(method).build();
		assertTrue(ir.blocks().stream().anyMatch(block -> !block.phis().isEmpty()));
	}

	@Test
	void buildsPhiForLoopHeader() {
		// Rough pseudocode for the method being tested:
		// int loop() {
		//     int i = 0;
		//     while (i < 3) {
		//         i += 1;
		//     }
		//     return i;
		// }
		Label loop = new Label();
		Label end = new Label();
		MethodMember method = new MethodMember("loop", Types.methodTypeFromDescriptor("()I"),
				org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC);
		method.setCode(IrTestUtils.code(4, 0,
				new ConstInstruction(0, 0),
				new ConstInstruction(1, 0),
				loop,
				new ConstInstruction(2, 3),
				new BranchInstruction(BranchInstruction.TEST_IF_GE, 0, 2, end),
				new BinaryInstruction(ADD_INT, 1, 1, 0),
				new ConstInstruction(3, 1),
				new BinaryInstruction(ADD_INT, 0, 0, 3),
				new GotoInstruction(loop),
				end,
				new ReturnInstruction(1)));

		// The loop header block should contain a phi function for the loop variable (register 0)
		// that merges the initial value (0) and the updated value from the back edge of the loop (register 3).
		IrMethod ir = new IrBuilder(method).build();
		assertFalse(ir.blocks().stream()
						.filter(block -> block.startOffset() == loop.position())
						.findFirst()
						.orElseThrow(() -> new IllegalStateException("Expected loop header block not found"))
						.phis()
						.isEmpty(),
				"Expected loop header block to contain a phi function for the loop variable");
	}

	@Test
	void buildsPhiWhenOnlyPredecessorIsLaterBlock() {
		// Rough pseudocode for the method being tested:
		// int singleGotoStart() {
		//     goto later;
		//   early:
		//     int x = 7;
		//     return x;
		//   later:
		//     goto early;
		// }
		Label early = new Label();
		Label later = new Label();
		MethodMember method = new MethodMember("singleGotoStart", Types.methodTypeFromDescriptor("()I"),
				org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC);
		method.setCode(IrTestUtils.code(1, 0,
				new GotoInstruction(later),
				early,
				new ConstInstruction(0, 7),
				new ReturnInstruction(0),
				later,
				new GotoInstruction(early)));

		IrMethod ir = assertDoesNotThrow(() -> new IrBuilder(method).build());
		assertFalse(ir.blocks().stream()
						.filter(block -> block.startOffset() == early.position())
						.findFirst()
						.orElseThrow(() -> new IllegalStateException("Expected header block not found"))
						.phis()
						.isEmpty(),
				"Expected early block to fall back to phis when its only predecessor is built later");
	}

	@Test
	void carriesPendingResultAcrossProtectedInvokeSplit() {
		// Rough pseudocode for the method being tested:
		// Integer boxedTry(int x) {
		//     try {
		//         return Integer.valueOf(x);
		//     } catch (Throwable t) {
		//         return 0;
		//     }
		// }
		Label start = new Label();
		Label end = new Label();
		Label handler = new Label();
		MethodMember method = new MethodMember("boxedTry", Types.methodTypeFromDescriptor("(I)Ljava/lang/Integer;"),
				org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC);
		Code code = IrTestUtils.code(2, 1,
				start,
				new InvokeInstruction(Invoke.STATIC, Types.instanceType(Integer.class), "valueOf",
						Types.methodTypeFromDescriptor("(I)Ljava/lang/Integer;"), 1),
				new MoveResultInstruction(Result.OBJECT, 0),
				end,
				new ReturnInstruction(0, Return.OBJECT),
				handler,
				new MoveExceptionInstruction(1),
				new ConstInstruction(0, 0),
				new ReturnInstruction(0, Return.OBJECT));
		code.addTryCatch(new TryCatch(start, end, List.of(new Handler(handler, Types.instanceType(Throwable.class)))));
		method.setCode(code);

		assertDoesNotThrow(() -> new IrBuilder(method).build());
	}

	@Test
	void splitsProtectedThrowingInstructionAfterSetup() {
		// Rough pseudocode for the method being tested:
		// void protectedInvokeAfterSetup() {
		//     try {
		//         System.gc();
		//     } catch (Throwable t) {
		//         // Handler logic (not relevant for the test)
		//     }
		// }
		Label start = new Label();
		Label invoke = new Label();
		Label end = new Label();
		Label handler = new Label();
		MethodMember method = new MethodMember("protectedInvokeAfterSetup", Types.methodTypeFromDescriptor("()V"),
				org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC);
		Code code = IrTestUtils.code(1, 0,
				start,
				new ConstInstruction(0, 1),
				invoke,
				new InvokeInstruction(Invoke.STATIC, Types.instanceType(System.class), "gc",
						Types.methodTypeFromDescriptor("()V")),
				end,
				new ReturnInstruction(),
				handler,
				new MoveExceptionInstruction(0),
				new ReturnInstruction());
		code.addTryCatch(new TryCatch(start, end, List.of(new Handler(handler, Types.instanceType(Throwable.class)))));
		method.setCode(code);

		IrMethod ir = new IrBuilder(method).build();
		assertTrue(ir.blocks().stream().anyMatch(block -> block.startOffset() == invoke.position()),
				"Expected the protected invoke to start its own block");
		assertTrue(ir.blocks().stream()
						.filter(block -> block.startOffset() == start.position())
						.findFirst()
						.orElseThrow(() -> new IllegalStateException("Expected setup block not found"))
						.exceptionalSuccessors()
						.isEmpty(),
				"Expected the setup block to remain outside the exceptional edge");
		var invokeBlock = ir.blocks().stream()
				.filter(block -> block.startOffset() == invoke.position())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Expected invoke block not found"));
		assertFalse(invokeBlock.exceptionalSuccessors().isEmpty(),
				"Expected the protected invoke block to retain the handler edge");
		assertNotNull(invokeBlock.exceptionalExitState(),
				"Expected the protected invoke block to carry exceptional state");
	}
}
