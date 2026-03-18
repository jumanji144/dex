package me.darknet.dex.convert.ir.optimize;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class BaseIrOptimizer implements IrOptimizer {
	private static final Object MULTIPLE_CONSUMERS = new Object();
	private final IrOptimizer innerClassRelationRestoration = new InnerClassRelationRestorationOptimizer();
	private final IrOptimizer constantFolding = new ConstantFoldingOptimizer();
	private final IrOptimizer branchFolding = new BranchFoldingOptimizer();

	@Override
	public void optimizeProgram(@NotNull IrOptimizationContext context) {
		innerClassRelationRestoration.optimizeProgram(context);

		// TODO: Implement additional program-scoped optimizations
		//  - Small method inlining
		//  - Detect possible constant values in methods and look for matching fields to replace them with
		//  - Detect and prune Kotlin associated junk with cleaner Java alternatives
		//    - Named parameters (e.g. foo$default) that can be replaced with varargs or default arguments
		//    - Companion objects that can be replaced with static members
		//    - Detect and prune Java synthetic members (e.g. access$000) that can be replaced with direct accesses
	}

	@Override
	public void optimizeMethod(@NotNull IrOptimizationContext context, @NotNull IrMethod method) {
		simplifyPhis(method);
		constantFolding.optimizeMethod(context, method);
		branchFolding.optimizeMethod(context, method);
		markStackOnlySingleUseValues(method);
		eliminateDeadPureOps(method);
		simplifyPhis(method);
		eliminateDeadPhis(method);
		simplifyPhis(method);
	}

	protected void simplifyPhis(@NotNull IrMethod method) {
		for (IrBlock block : method.blocks()) {
			for (IrPhi phi : block.phis()) {
				IrValue candidate = null;
				boolean trivial = true;
				for (IrValue input : phi.operands().values()) {
					IrValue canonical = input.canonical();
					if (canonical == phi) continue;
					if (candidate == null) {
						candidate = canonical;
					} else if (candidate != canonical) {
						trivial = false;
						break;
					}
				}
				if (trivial && candidate != null) {
					phi.replaceWith(candidate);
				}
			}
		}
	}

	protected void eliminateDeadPureOps(@NotNull IrMethod method) {
		boolean changed;
		do {
			changed = false;
			Map<IrValue, Integer> uses = countUses(method);
			for (IrBlock block : method.blocks()) {
				Iterator<IrStmt> iterator = block.statements().iterator();
				while (iterator.hasNext()) {
					IrStmt statement = iterator.next();
					if (!(statement instanceof IrOp op) || !op.pure())
						continue;
					if (uses.getOrDefault(op.canonical(), 0) != 0)
						continue;
					iterator.remove();
					changed = true;
				}
			}
		} while (changed);
	}

	protected void eliminateDeadPhis(@NotNull IrMethod method) {
		boolean changed;
		do {
			changed = false;
			Map<IrValue, Integer> uses = countUses(method);
			for (IrBlock block : method.blocks()) {
				Iterator<IrPhi> iterator = block.phis().iterator();
				while (iterator.hasNext()) {
					IrPhi phi = iterator.next();
					if (phi.canonical() != phi)
						continue;
					if (uses.getOrDefault(phi, 0) != 0)
						continue;
					iterator.remove();
					changed = true;
				}
			}
		} while (changed);
	}

	protected void markStackOnlySingleUseValues(@NotNull IrMethod method) {
		Map<IrValue, Integer> uses = new HashMap<>();
		Map<IrValue, Object> singleConsumers = new HashMap<>();
		for (IrBlock block : method.blocks()) {
			for (IrPhi phi : block.phis()) {
				for (IrValue input : phi.operands().values()) {
					recordUse(uses, singleConsumers, input, phi);
				}
			}
			for (IrStmt statement : block.statements()) {
				switch (statement) {
					case IrOp op -> op.inputs().forEach(input -> recordUse(uses, singleConsumers, input, statement));
					case IrEffect effect ->
							effect.inputs().forEach(input -> recordUse(uses, singleConsumers, input, statement));
					case IrTerminator ignored -> {
					}
				}
			}
			if (block.terminator() != null) {
				block.terminator().inputs().forEach(input -> recordUse(uses, singleConsumers, input, block.terminator()));
			}
		}

		for (IrBlock block : method.blocks()) {
			for (int i = 0; i < block.statements().size(); i++) {
				IrStmt statement = block.statements().get(i);
				if (!(statement instanceof IrOp op) || op.canonical() != op || !canStackInline(op))
					continue;
				if (uses.getOrDefault(op, 0) != 1)
					continue;
				Object consumer = singleConsumers.get(op);
				if (consumer == null)
					continue;
				if (consumer == block.terminator() && i == block.statements().size() - 1) {
					op.stackOnly(true);
					continue;
				}
				if (!(consumer instanceof IrStmt consumerStatement))
					continue;
				int consumerIndex = block.statements().indexOf(consumerStatement);
				if (consumerIndex == i + 1)
					op.stackOnly(true);
			}
		}
	}

	protected void recordUse(@NotNull Map<IrValue, Integer> uses,
	                         @NotNull Map<IrValue, Object> singleConsumers,
	                         @NotNull IrValue input, @NotNull Object consumer) {
		IrValue canonical = input.canonical();
		uses.merge(canonical, 1, Integer::sum);
		Object existing = singleConsumers.get(canonical);
		if (existing == null) {
			singleConsumers.put(canonical, consumer);
		} else if (existing != consumer) {
			singleConsumers.put(canonical, MULTIPLE_CONSUMERS);
		}
	}

	protected boolean canStackInline(@NotNull IrOp op) {
		return !(op.payload() instanceof me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction);
	}

	protected @NotNull Map<IrValue, Integer> countUses(@NotNull IrMethod method) {
		Map<IrValue, Integer> uses = new HashMap<>();
		for (IrBlock block : method.blocks()) {
			for (IrPhi phi : block.phis()) {
				for (IrValue input : phi.operands().values()) {
					uses.merge(input.canonical(), 1, Integer::sum);
				}
			}
			for (IrStmt statement : block.statements()) {
				switch (statement) {
					case IrOp op -> op.inputs().forEach(value -> uses.merge(value.canonical(), 1, Integer::sum));
					case IrEffect effect ->
							effect.inputs().forEach(value -> uses.merge(value.canonical(), 1, Integer::sum));
					case IrTerminator terminator ->
							terminator.inputs().forEach(value -> uses.merge(value.canonical(), 1, Integer::sum));
				}
			}
			if (block.terminator() != null) {
				block.terminator().inputs().forEach(value -> uses.merge(value.canonical(), 1, Integer::sum));
			}
		}
		return uses;
	}
}
