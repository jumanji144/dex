package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Immutable bridge between optimization plans and value emission.  Dynamic
 * operand-stack carry remains outside this snapshot because it is established
 * while traversing a block; all producer/consumer inline decisions are fixed
 * here before bytecode emission begins.
 */
final class JvmMaterializationPlan {
	private final Map<IrOp, JvmSingleUsePlan> inlinePlans;

	private JvmMaterializationPlan(@NotNull Map<IrOp, JvmSingleUsePlan> inlinePlans) {
		this.inlinePlans = Collections.unmodifiableMap(new IdentityHashMap<>(inlinePlans));
	}

	static @NotNull JvmMaterializationPlan from(@NotNull Iterable<JvmSingleUsePlan> plans) {
		Map<IrOp, JvmSingleUsePlan> inlinePlans = new IdentityHashMap<>();
		for (JvmSingleUsePlan plan : plans) {
			if (!plan.accepted()) continue;
			for (IrOp operation : plan.candidate().operations())
				inlinePlans.put(operation, plan);
		}
		return new JvmMaterializationPlan(inlinePlans);
	}

	static @NotNull JvmMaterializationPlan from(@NotNull Map<IrOp, JvmSingleUsePlan> plans) {
		return new JvmMaterializationPlan(plans);
	}

	@NotNull JvmMaterializationDecision decision(@NotNull IrValue value, @Nullable IrStmt consumer) {
		IrValue canonical = value.canonical();
		if (canonical.isUnknown() || canonical.isImprecise())
			return new JvmMaterializationDecision(JvmMaterializationKind.TYPED_FALLBACK, null,
					"unknown or imprecise value requires typed fallback");
		if (canonical instanceof me.darknet.dex.convert.ir.value.IrConstant)
			return new JvmMaterializationDecision(JvmMaterializationKind.CONSTANT, null, "constant value");
		if (!(canonical instanceof IrOp operation))
			return new JvmMaterializationDecision(JvmMaterializationKind.LOCAL, null, "authoritative local");
		JvmSingleUsePlan plan = inlinePlans.get(operation);
		if (plan == null || plan.candidate().consumer() != consumer)
			return new JvmMaterializationDecision(JvmMaterializationKind.LOCAL, null, "authoritative local");
		JvmMaterializationKind kind = plan.candidate().mode() == JvmSingleUseCandidate.Mode.RECEIVER_CHAIN
				? JvmMaterializationKind.RECEIVER_CHAIN : JvmMaterializationKind.SINGLE_USE_INLINE;
		return new JvmMaterializationDecision(kind, plan, "accepted single-use proof");
	}

	boolean skipsSeparateEmission(@NotNull IrOp operation) {
		return inlinePlans.containsKey(operation);
	}

	@Nullable JvmSingleUsePlan planFor(@NotNull IrOp operation) {
		return inlinePlans.get(operation);
	}
}
