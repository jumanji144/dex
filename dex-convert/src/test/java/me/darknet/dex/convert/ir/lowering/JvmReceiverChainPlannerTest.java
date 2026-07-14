package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrOpKind;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

class JvmReceiverChainPlannerTest {
	@Test
	void acceptsReceiverReturningChainToReturn() {
		IrBlock block = new IrBlock(0, 0);
		var owner = Types.instanceTypeFromInternalName("example/Builder");
		IrConstant receiver = new IrConstant(1, owner, new Object(), false);
		IrOp first = invoke(2, owner, owner, "first", "()Lexample/Builder;", Invoke.VIRTUAL, receiver);
		IrOp second = invoke(3, owner, owner, "second", "()Lexample/Builder;", Invoke.VIRTUAL, first);
		block.statements().addAll(List.of(first, second));
		block.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(second),
				new ReturnInstruction(0, Return.OBJECT)));

		JvmSingleUseCandidate candidate = receiverCandidate(method(block, "()Lexample/Builder;"));
		assertTrue(candidate.proofEligible(), candidate.proofReason());
		assertTrue(candidate.operations().contains(first));
		assertTrue(candidate.operations().contains(second));
	}

	@Test
	void rejectsReceiverChainWithInterposedOperation() {
		IrBlock block = new IrBlock(0, 0);
		var owner = Types.instanceTypeFromInternalName("example/Builder");
		IrConstant receiver = new IrConstant(1, owner, new Object(), false);
		IrOp first = invoke(2, owner, owner, "first", "()Lexample/Builder;", Invoke.VIRTUAL, receiver);
		IrOp noise = new IrOp(3, owner, IrOpKind.NEW_INSTANCE, List.of(),
				new me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction(3, owner));
		IrOp second = invoke(4, owner, owner, "second", "()Lexample/Builder;", Invoke.VIRTUAL, first);
		block.statements().addAll(List.of(first, noise, second));
		block.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(second),
				new ReturnInstruction(0, Return.OBJECT)));

		JvmSingleUseCandidate candidate = receiverCandidate(method(block, "()Lexample/Builder;"));
		assertFalse(candidate.proofEligible());
		assertTrue(candidate.proofReason().contains("adjacent"), candidate.proofReason());
	}

	@Test
	void acceptsConstructorBackedFluentChainToReturn() {
		IrBlock block = new IrBlock(0, 0);
		var builder = Types.instanceTypeFromInternalName("example/Builder");
		var text = Types.instanceType(String.class);
		IrOp allocation = new IrOp(1, builder, IrOpKind.NEW_INSTANCE, List.of(),
				new me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction(0, builder));
		IrOp constructor = invoke(2, Types.VOID, builder, "<init>", "()V", Invoke.DIRECT, allocation);
		IrOp setText = invoke(3, builder, builder, "setText", "(Ljava/lang/String;)Lexample/Builder;",
				Invoke.VIRTUAL, allocation, new IrConstant(4, text, "hello", false));
		IrOp build = invoke(5, Types.instanceType(Object.class), builder, "build", "()Ljava/lang/Object;",
				Invoke.VIRTUAL, setText);
		block.statements().addAll(List.of(allocation, constructor, setText, build));
		block.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(build),
				new ReturnInstruction(0, Return.OBJECT)));

		IrMethod method = method(block, "()Ljava/lang/Object;");
		JvmSingleUseCandidate candidate = receiverCandidate(method);

		candidate.operations().forEach(operation -> assertTrue(operation.semantics().complete(), operation.toString()));
		assertTrue(candidate.proofEligible(), candidate.proofReason() + " ops=" + candidate.operations().stream()
				.map(operation -> operation.kind() + ":" + operation.type() + ":" + operation.semantics().complete())
				.toList());
		assertTrue(candidate.operations().contains(constructor));
		assertTrue(candidate.operations().contains(setText));
	}

	@Test
	void rejectsChainWithUnknownReceiverInput() {
		IrBlock block = new IrBlock(0, 0);
		var owner = Types.instanceTypeFromInternalName("example/Builder");
		IrUnknown unknown = new IrUnknown(1, owner, me.darknet.dex.convert.ir.value.IrTypeKind.REFERENCE, null, 12);
		IrOp first = invoke(2, owner, owner, "first", "()Lexample/Builder;", Invoke.VIRTUAL, unknown);
		IrOp second = invoke(3, owner, owner, "second", "()Lexample/Builder;", Invoke.VIRTUAL, first);
		block.statements().addAll(List.of(first, second));
		block.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(second),
				new ReturnInstruction(0, Return.OBJECT)));

		IrMethod method = method(block, "()Ljava/lang/Object;");
		JvmSingleUseCandidate candidate = receiverCandidate(method);

		assertFalse(candidate.proofEligible());
	}

	private static JvmSingleUseCandidate receiverCandidate(IrMethod method) {
		JvmOptimizationGuards guards = new JvmOptimizationGuards(method, LoweringUseGraph.analyze(method));
		return JvmSingleUsePlanner.discover(method, LoweringUseGraph.analyze(method), guards)
				.stream().filter(value -> value.mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN)
				.findFirst().orElseThrow();
	}

	private static IrOp invoke(int id, me.darknet.dex.tree.type.ClassType result,
	                           me.darknet.dex.tree.type.ReferenceType owner, String name, String descriptor,
	                           int kind, me.darknet.dex.convert.ir.value.IrValue receiver, IrConstant... arguments) {
		List<me.darknet.dex.convert.ir.value.IrValue> inputs = new java.util.ArrayList<>();
		inputs.add(receiver);
		inputs.addAll(List.of(arguments));
		return new IrOp(id, result, IrOpKind.INVOKE, inputs,
				new InvokeInstruction(kind, owner, name, Types.methodTypeFromDescriptor(descriptor), id));
	}

	private static IrMethod method(IrBlock block, String descriptor) {
		return new IrMethod(new MethodMember("chain", Types.methodTypeFromDescriptor(descriptor),
				ACC_PUBLIC | ACC_STATIC), 8, List.of(block), block, List.of());
	}
}
