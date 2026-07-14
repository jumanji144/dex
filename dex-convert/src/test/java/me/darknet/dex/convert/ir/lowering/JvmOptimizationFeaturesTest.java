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
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

class JvmOptimizationFeaturesTest {
	@Test
	void aggressiveDefaultsToAllFeatures() {
		JvmOptimizationFeatures features = JvmOptimizationFeatures.all();
		assertEquals(EnumSet.allOf(JvmOptimizationFeature.class).size(), features.enabled().size());
		for (JvmOptimizationFeature feature : JvmOptimizationFeature.values())
			assertTrue(features.contains(feature));
	}

	@Test
	void featureSetsAreImmutableAndIndependent() {
		JvmOptimizationFeatures none = JvmOptimizationFeatures.none();
		JvmOptimizationFeatures cleanup = none.withEnabled(JvmOptimizationFeature.CLEANUP_REGIONS);
		JvmOptimizationFeatures tails = cleanup.withEnabled(JvmOptimizationFeature.CLEANUP_TAILS);

		assertFalse(none.contains(JvmOptimizationFeature.CLEANUP_REGIONS));
		assertTrue(cleanup.contains(JvmOptimizationFeature.CLEANUP_REGIONS));
		assertFalse(cleanup.contains(JvmOptimizationFeature.CLEANUP_TAILS));
		assertTrue(tails.contains(JvmOptimizationFeature.CLEANUP_TAILS));
		assertThrows(UnsupportedOperationException.class,
				() -> cleanup.enabled().add(JvmOptimizationFeature.MONITOR_REGIONS));
	}

	@Test
	void disablingOneFeatureDoesNotDisableOthers() {
		JvmOptimizationFeatures features = JvmOptimizationFeatures.all()
				.withDisabled(JvmOptimizationFeature.RECEIVER_CHAINS);
		assertFalse(features.contains(JvmOptimizationFeature.RECEIVER_CHAINS));
		assertTrue(features.contains(JvmOptimizationFeature.SINGLE_USE_INLINE));
		assertTrue(features.contains(JvmOptimizationFeature.CLEANUP_TAILS));
	}

	@Test
	void guardedPolicyIgnoresAggressiveFeatureGates() {
		IrMethod method = receiverMethod();
		LoweringUseGraph graph = LoweringUseGraph.analyze(method);
		JvmSingleUseCandidate candidate = receiverCandidate(method, graph);
		JvmOptimizationPlan plan = new JvmOptimizationPlan(method, JvmLoweringPolicy.GUARDED_OPTIMIZED,
				graph, JvmOptimizationFeatures.none());

		JvmOptimizationDecision decision = plan.receiverChain(candidate);
		assertTrue(decision.accepted());
		assertEquals(JvmProofStrength.GUARDED, decision.strength());
	}

	@Test
	void disabledAggressiveReceiverGateHasStableDecision() {
		IrMethod method = receiverMethod();
		LoweringUseGraph graph = LoweringUseGraph.analyze(method);
		JvmSingleUseCandidate candidate = receiverCandidate(method, graph);
		JvmOptimizationPlan plan = new JvmOptimizationPlan(method, JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED,
				graph, JvmOptimizationFeatures.all().withDisabled(JvmOptimizationFeature.RECEIVER_CHAINS));

		JvmOptimizationDecision decision = plan.receiverChain(candidate);
		assertFalse(decision.accepted());
		assertEquals(JvmOptimizationFeature.RECEIVER_CHAINS, decision.gate());
		assertEquals(JvmProofStrength.NONE, decision.strength());
		assertEquals("feature gate disabled", decision.reason());
	}

	private static JvmSingleUseCandidate receiverCandidate(IrMethod method, LoweringUseGraph graph) {
		JvmOptimizationGuards guards = new JvmOptimizationGuards(method, graph);
		return JvmSingleUsePlanner.discover(method, graph, guards).stream()
				.filter(value -> value.mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN)
				.findFirst().orElseThrow();
	}

	private static IrMethod receiverMethod() {
		IrBlock block = new IrBlock(0, 0);
		ReferenceType owner = Types.instanceTypeFromInternalName("example/Builder");
		IrConstant receiver = new IrConstant(1, owner, new Object(), false);
		IrOp first = invoke(2, owner, owner, "first", "()Lexample/Builder;", receiver);
		IrOp second = invoke(3, owner, owner, "second", "()Lexample/Builder;", first);
		block.statements().addAll(List.of(first, second));
		block.terminator(new IrTerminator(IrTerminatorKind.RETURN, List.of(second),
				new ReturnInstruction(0, Return.OBJECT)));
		return new IrMethod(new MethodMember("chain", Types.methodTypeFromDescriptor("()Lexample/Builder;"),
				ACC_PUBLIC | ACC_STATIC), 8, List.of(block), block, List.of());
	}

	private static IrOp invoke(int id, me.darknet.dex.tree.type.ClassType result,
	                           ReferenceType owner, String name, String descriptor, IrValue receiver,
	                           IrValue... arguments) {
		java.util.ArrayList<IrValue> inputs = new java.util.ArrayList<>();
		inputs.add(receiver);
		inputs.addAll(List.of(arguments));
		return new IrOp(id, result, IrOpKind.INVOKE, inputs,
				new InvokeInstruction(Invoke.VIRTUAL, owner, name, Types.methodTypeFromDescriptor(descriptor), id));
	}
}
