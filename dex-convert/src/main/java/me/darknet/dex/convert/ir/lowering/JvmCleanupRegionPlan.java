package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.IrExceptionHandler;
import me.darknet.dex.convert.ir.IrExceptionRegion;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrParameter;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Label;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable description of one DEX resource lifecycle as it will be shaped by
 * the JVM backend.  A lifecycle is deliberately a data object: matching and
 * policy selection happen before bytecode labels are emitted, while the
 * emitter only consumes the selected plan.
 */
record JvmCleanupRegionPlan(
		@NotNull IrExceptionRegion region,
		@NotNull IrExceptionHandler handler,
		@NotNull IrValue resource,
		int resourceLocal,
		@Nullable IrOp acquisition,
		@Nullable IrBlock nullResourceBlock,
		@NotNull List<IrBlock> protectedBody,
		@Nullable IrOp normalClose,
		@Nullable IrExceptionEdge closeException,
		@Nullable IrValue primaryException,
		@Nullable IrOp suppressedException,
		@Nullable IrTerminator rethrow,
		@Nullable Label jvmStartLabel,
		@Nullable Label jvmEndLabel,
		@NotNull List<JvmCleanupRegionPlan> nestedPlans,
		@NotNull JvmOptimizationDecision decision) {

	JvmCleanupRegionPlan {
		protectedBody = List.copyOf(protectedBody);
		nestedPlans = List.copyOf(nestedPlans);
	}

	static @Nullable JvmCleanupRegionPlan match(@NotNull IrMethod method,
	                                            @NotNull IrExceptionRegion region,
	                                            @NotNull Map<Integer, IrBlock> blockByOffset,
	                                            @Nullable Label jvmStartLabel,
	                                            @Nullable Label jvmEndLabel) {
		if (region.protectedBlocks().isEmpty() || region.handlers().isEmpty()) return null;
		for (IrExceptionHandler handler : region.handlers()) {
			IrBlock handlerBlock = handler.handlerBlock();
			Set<IrValue> candidates = resourceCandidates(handlerBlock, region);
			// DEX try-with-resources lowering has two close families: the normal
			// fall-through closes and the handler closes used when acquisition or
			// body execution throws.  Use the handler-reachable close to identify
			// the resource and its exceptional edge, but retain the source-ordered
			// normal close separately in the lifecycle plan.  This keeps resource
			// matching stable while preventing the handler close from becoming the
			// normal continuation used by composite JVM layout.
			IrOp handlerClose = hasNestedClosePaths(method)
					? findCloseInHandler(handlerBlock, candidates)
					: findCloseInMethod(method, candidates);
			IrValue resource = findResource(region, handlerBlock, handlerClose, blockByOffset);
			IrOp close = resource == null ? handlerClose : findNormalCloseInMethod(method, resource);
			if (resource == null || close == null)
				continue;
			if (resource == null || !isReference(resource)) continue;
			IrBlock nullBlock = findNullResourceBlock(region, handlerBlock, resource, blockByOffset);
			// A cleanup plan describes a resource lifecycle, rather than an
			// arbitrary catch block.  Requiring a close operation keeps ordinary
			// exception handlers on the existing lowering path.
			if (close == null) continue;

			IrOp acquisition = findAcquisition(method, resource, close);
			IrExceptionEdge closeException = findThrowingEdge(method, handlerClose == null ? close : handlerClose);
			IrOp suppressed = findInvokeInHandler(handlerBlock, "addSuppressed");
			IrTerminator rethrow = findTerminatorInHandler(handlerBlock, IrTerminatorKind.THROW);
			IrValue primary = findPrimaryException(handlerBlock, suppressed, rethrow);
			return new JvmCleanupRegionPlan(region, handler, resource,
					resource.hasLocal() ? resource.local() : -1, acquisition, nullBlock,
					region.protectedBlocks(), close, closeException, primary, suppressed, rethrow,
					jvmStartLabel, jvmEndLabel, List.of(),
					JvmOptimizationDecision.rejected(JvmOptimizationFeature.CLEANUP_REGIONS, "resource-region", region.startOffset(),
							"resource lifecycle candidate not yet selected"));
		}
		return null;
	}

	JvmCleanupRegionPlan withDecision(@NotNull JvmOptimizationDecision replacement) {
		return new JvmCleanupRegionPlan(region, handler, resource, resourceLocal, acquisition,
				nullResourceBlock, protectedBody, normalClose, closeException, primaryException, suppressedException,
				rethrow, jvmStartLabel, jvmEndLabel, nestedPlans, replacement);
	}

	JvmCleanupRegionPlan withNested(@NotNull List<JvmCleanupRegionPlan> nested) {
		return new JvmCleanupRegionPlan(region, handler, resource, resourceLocal, acquisition,
				nullResourceBlock, protectedBody, normalClose, closeException, primaryException, suppressedException,
				rethrow, jvmStartLabel, jvmEndLabel, nested, decision);
	}

	/**
	 * Returns the direct resource children in acquisition order.  The matcher
	 * may discover all contained regions, so this method removes transitive
	 * descendants before a layout planner composes the layers.
	 */
	@NotNull List<JvmCleanupRegionPlan> directNestedLayers() {
		List<JvmCleanupRegionPlan> result = new ArrayList<>();
		for (JvmCleanupRegionPlan candidate : nestedPlans) {
			boolean containedByAnother = nestedPlans.stream().anyMatch(parent -> parent != candidate
					&& contains(parent.region(), candidate.region()));
			if (!containedByAnother) result.add(candidate);
		}
		result.sort(java.util.Comparator.comparingInt(plan -> plan.region().startOffset()));
		return List.copyOf(result);
	}

	/**
	 * Proves that the selected plans can be represented as one nested resource
	 * lifecycle.  This is intentionally conservative: an unproven child is
	 * retained as an independent local-first cleanup plan rather than being
	 * folded into its parent.
	 */
	boolean nestedLayerProof(@NotNull IrMethod method) {
		if (nestedPlans.isEmpty()) return true;
		if (!hasMaterializedResource() || acquisition == null || normalClose == null) return false;
		Set<IrValue> resources = Collections.newSetFromMap(new IdentityHashMap<>());
		resources.add(resource.canonical());
		List<JvmCleanupRegionPlan> layers = new ArrayList<>();
		layers.add(this);
		layers.addAll(directNestedLayers());
		for (JvmCleanupRegionPlan child : layers.subList(1, layers.size())) {
			if (!contains(region, child.region()) || child.acquisition() == null
					|| child.normalClose() == null || !child.hasMaterializedResource()
					|| !resources.add(child.resource().canonical())) return false;
			int parentAcquire = operationOffset(method, acquisition);
			int childAcquire = operationOffset(method, child.acquisition());
			int parentClose = operationOffset(method, normalClose);
			int childClose = operationOffset(method, child.normalClose());
			if (parentAcquire < 0 || childAcquire < 0 || parentClose < 0 || childClose < 0
					|| parentAcquire >= childAcquire || childClose >= parentClose) return false;
		}
		List<JvmCleanupRegionPlan> children = directNestedLayers();
		for (int i = 0; i < children.size(); i++) {
			JvmCleanupRegionPlan left = children.get(i);
			for (int j = i + 1; j < children.size(); j++) {
				JvmCleanupRegionPlan right = children.get(j);
				if (overlaps(left.region(), right.region())) return false;
			}
		}
		return true;
	}

	private static int operationOffset(@NotNull IrMethod method, @Nullable IrOp operation) {
		if (operation == null) return -1;
		for (IrBlock block : method.blocks())
			if (block.statements().contains(operation)) return block.startOffset();
		return -1;
	}

	boolean hasNullResourcePath() {
		return nullResourceBlock != null;
	}

	/**
	 * Returns only the blocks implementing the null-resource throw.  The
	 * enclosing protected body must remain in its original JVM range.
	 */
	@NotNull List<IrBlock> nullResourceSequence(@NotNull Map<Integer, IrBlock> blockByOffset) {
		if (nullResourceBlock == null) return List.of();
		List<IrBlock> result = new ArrayList<>();
		Set<IrBlock> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		IrBlock current = nullResourceBlock;
		while (current != null && seen.add(current)) {
			result.add(current);
			IrTerminator terminator = current.terminator();
			if (terminator == null || terminator.kind() != IrTerminatorKind.GOTO) break;
			IrBlock next = gotoTarget(current, terminator, blockByOffset);
			if (next == null || !isTransparentNullThrowGlue(next)) break;
			current = next;
		}
		return List.copyOf(result);
	}

	private static boolean isTransparentNullThrowGlue(@NotNull IrBlock block) {
		return block.statements().isEmpty()
				&& block.phis().isEmpty()
				&& block.exceptionValue() == null
				&& block.exceptionalSuccessors().isEmpty();
	}

	boolean hasSuppressedExceptionPath() {
		return suppressedException != null && rethrow != null;
	}

	int acquisitionOffset(@NotNull IrMethod method) {
		if (acquisition != null) return operationOffset(method, acquisition);
		// A parameter-owned resource (for example the socket passed to a
		// connection handler) has no DEX acquisition instruction.  Its resource
		// lifetime begins at the protected region boundary.
		return resource.canonical() instanceof IrParameter ? region.startOffset() : -1;
	}

	int normalCloseOffset(@NotNull IrMethod method) {
		return operationOffset(method, normalClose);
	}

	private static @Nullable IrValue findPrimaryException(@NotNull IrBlock handler,
	                                                       @Nullable IrOp suppressed,
	                                                       @Nullable IrTerminator rethrow) {
		if (suppressed != null && !suppressed.inputs().isEmpty())
			return suppressed.inputs().getFirst().canonical();
		if (rethrow != null && !rethrow.inputs().isEmpty())
			return rethrow.inputs().getFirst().canonical();
		return handler.exceptionValue() == null ? null : handler.exceptionValue().canonical();
	}

	boolean hasMaterializedResource() {
		IrValue value = resource.canonical();
		// Method parameters have authoritative JVM argument slots even though the
		// allocator deliberately does not write a synthetic local number onto the
		// IR parameter.  Treating a reference parameter as materialized allows a
		// nested lifecycle to model an incoming socket without inventing an
		// acquisition operation.
		if (value instanceof IrParameter) return isReference(value);
		return !(value instanceof IrUnknown) && !value.isImprecise()
				&& !value.stackOnly() && (value.constantValue() != null || value.hasLocal());
	}

	private static @Nullable IrValue findResource(@NotNull IrExceptionRegion region,
	                                             @NotNull IrBlock handlerBlock,
	                                             @Nullable IrOp close,
	                                             @NotNull Map<Integer, IrBlock> blockByOffset) {
		if (close != null && !close.inputs().isEmpty()) {
			IrValue receiver = close.inputs().getFirst().canonical();
			if (isReference(receiver)) return receiver;
		}
		IrTerminator cleanupTerminator = handlerBlock.terminator();
		IrBlock cleanup = gotoTarget(handlerBlock, cleanupTerminator, blockByOffset);
		if (cleanup != null && cleanup.terminator() != null
				&& cleanup.terminator().kind() == IrTerminatorKind.IF_ZERO
				&& !cleanup.terminator().inputs().isEmpty()) {
			IrValue value = cleanup.terminator().inputs().getFirst().canonical();
			if (isReference(value)) return value;
		}
		for (IrValue value : region.protectedBlocks().stream()
				.flatMap(block -> block.statements().stream())
				.flatMap(statement -> inputsOf(statement).stream())
				.map(IrValue::canonical)
				.filter(JvmCleanupRegionPlan::isReference)
				.toList()) return value;
		return null;
	}

	private static Set<IrValue> resourceCandidates(@NotNull IrBlock handlerBlock,
	                                               @NotNull IrExceptionRegion region) {
		Set<IrValue> values = Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrBlock block : region.protectedBlocks()) {
			addPhiValues(block, values);
			for (IrStmt statement : block.statements())
				inputsOf(statement).forEach(value -> values.add(value.canonical()));
		}
		for (IrBlock block : reachableBlocks(handlerBlock)) {
			addPhiValues(block, values);
			for (IrStmt statement : block.statements())
				inputsOf(statement).forEach(value -> values.add(value.canonical()));
		}
		return values;
	}

	private static void addPhiValues(@NotNull IrBlock block, @NotNull Set<IrValue> values) {
		for (IrPhi phi : block.phis()) {
			values.add(phi.canonical());
			phi.operands().values().forEach(value -> values.add(value.canonical()));
		}
		if (block.exceptionValue() != null) values.add(block.exceptionValue().canonical());
	}

	private static @Nullable IrOp findCloseInHandler(@NotNull IrBlock handler,
	                                                  @NotNull Set<IrValue> candidates) {
		List<IrBlock> blocks = new ArrayList<>(reachableBlocks(handler));
		// Reachability traversal is intentionally identity-based and therefore
		// has no source-order guarantee.  Cleanup matching must choose the first
		// source close, just as the original method scan did, while still being
		// restricted to this handler's resource path.
		blocks.sort(Comparator.comparingInt(IrBlock::startOffset));
		for (IrBlock block : blocks) {
			for (IrStmt statement : block.statements()) {
				if (!(statement instanceof IrOp op)
						|| !(op.payload() instanceof InvokeInstruction invoke)
						|| !"close".equals(invoke.name())
						|| op.inputs().isEmpty()) continue;
				if (candidates.contains(op.inputs().getFirst().canonical())) return op;
			}
		}
		return null;
	}

	private static @Nullable IrOp findCloseInMethod(@NotNull IrMethod method,
	                                                @NotNull Set<IrValue> candidates) {
		for (IrBlock block : method.blocks())
			for (IrStmt statement : block.statements())
				if (statement instanceof IrOp op && op.payload() instanceof InvokeInstruction invoke
						&& "close".equals(invoke.name()) && !op.inputs().isEmpty()
						&& candidates.contains(op.inputs().getFirst().canonical())) return op;
		return null;
	}

	private static @Nullable IrOp findNormalCloseInMethod(@NotNull IrMethod method,
	                                                      @NotNull IrValue resource) {
		IrValue canonical = resource.canonical();
		for (IrBlock block : method.blocks())
			for (IrStmt statement : block.statements())
				if (statement instanceof IrOp op && op.payload() instanceof InvokeInstruction invoke
						&& "close".equals(invoke.name()) && !op.inputs().isEmpty()
						&& op.inputs().getFirst().canonical() == canonical)
					return op;
		return null;
	}

	private static boolean hasNestedClosePaths(@NotNull IrMethod method) {
		Set<IrValue> receivers = Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrBlock block : method.blocks())
			for (IrStmt statement : block.statements())
				if (statement instanceof IrOp op && op.payload() instanceof InvokeInstruction invoke
						&& "close".equals(invoke.name()) && !op.inputs().isEmpty())
					receivers.add(op.inputs().getFirst().canonical());
		return receivers.size() >= 3;
	}

	private static @Nullable IrOp findAcquisition(@NotNull IrMethod method,
	                                              @NotNull IrValue resource,
	                                              @Nullable IrOp close) {
		Set<IrValue> inputs = Collections.newSetFromMap(new IdentityHashMap<>());
		if (resource instanceof IrOp op) return op == close ? null : op;
		if (resource instanceof IrPhi phi)
			phi.operands().values().forEach(value -> inputs.add(value.canonical()));
		else return null;
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				if (!(statement instanceof IrOp op) || op == close) continue;
				if (inputs.contains(op.canonical()) && !isClose(op)) return op;
			}
		}
		return null;
	}


	private static @Nullable IrExceptionEdge findThrowingEdge(@NotNull IrMethod method, @NotNull IrOp op) {
		if (!(op.payload() instanceof Instruction instruction)) return null;
		for (IrBlock block : method.blocks())
			for (IrExceptionEdge edge : block.exceptionEdges())
				if (edge.throwingInstruction().equals(instruction)) return edge;
		return null;
	}

	private static @Nullable IrBlock findNullResourceBlock(@NotNull IrExceptionRegion region,
	                                                       @NotNull IrBlock handlerBlock,
	                                                       @NotNull IrValue resource,
	                                                       @NotNull Map<Integer, IrBlock> blockByOffset) {
		Set<IrValue> resourceForms = Collections.newSetFromMap(new IdentityHashMap<>());
		resourceForms.add(resource.canonical());
		if (resource instanceof IrPhi phi)
			phi.operands().values().forEach(value -> resourceForms.add(value.canonical()));
		List<IrBlock> search = new ArrayList<>(region.protectedBlocks());
		for (IrBlock block : reachableBlocks(handlerBlock))
			if (!search.contains(block)) search.add(block);
		for (IrBlock block : search) {
			IrTerminator terminator = block.terminator();
			if (terminator == null || terminator.kind() != IrTerminatorKind.IF_ZERO
					|| terminator.inputs().isEmpty()
					|| !resourceForms.contains(terminator.inputs().getFirst().canonical())) continue;
			IrBlock target = null;
			if (terminator.payload() instanceof BranchZeroInstruction branch)
				target = blockByOffset.get(branch.label().position());
			if (target == null && !block.successors().isEmpty()) target = block.successors().getFirst();
			if (isThrowPath(target, blockByOffset)) return target;
		}
		return null;
	}

	private static boolean isThrowPath(@Nullable IrBlock block, @NotNull Map<Integer, IrBlock> blockByOffset) {
		if (block == null) return false;
		if (block.terminator() != null && block.terminator().kind() == IrTerminatorKind.THROW) return true;
		IrTerminator terminator = block.terminator();
		if (terminator == null || terminator.kind() != IrTerminatorKind.GOTO) return false;
		IrBlock next = gotoTarget(block, terminator, blockByOffset);
		return next != null && next.terminator() != null && next.terminator().kind() == IrTerminatorKind.THROW;
	}

	private static @Nullable IrOp findInvokeInHandler(@NotNull IrBlock root, @NotNull String name) {
		for (IrStmt statement : reachableStatements(root))
			if (statement instanceof IrOp op && op.payload() instanceof InvokeInstruction invoke
					&& name.equals(invoke.name())) return op;
		return null;
	}

	private static @Nullable IrTerminator findTerminatorInHandler(@NotNull IrBlock root,
	                                                              @NotNull IrTerminatorKind kind) {
		Set<IrBlock> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<IrBlock> work = new ArrayDeque<>();
		work.add(root);
		while (!work.isEmpty()) {
			IrBlock block = work.removeFirst();
			if (!visited.add(block)) continue;
			if (block.terminator() != null && block.terminator().kind() == kind) return block.terminator();
			work.addAll(block.successors());
		}
		return null;
	}

	private static List<IrStmt> reachableStatements(@NotNull IrBlock root) {
		List<IrStmt> result = new ArrayList<>();
		for (IrBlock block : reachableBlocks(root)) {
			result.addAll(block.statements());
			if (block.terminator() != null) result.add(block.terminator());
		}
		return result;
	}

	private static List<IrBlock> reachableBlocks(@NotNull IrBlock root) {
		List<IrBlock> result = new ArrayList<>();
		Set<IrBlock> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<IrBlock> work = new ArrayDeque<>();
		work.add(root);
		while (!work.isEmpty()) {
			IrBlock block = work.removeFirst();
			if (!visited.add(block)) continue;
			result.add(block);
			work.addAll(block.successors());
		}
		return result;
	}

	private static List<IrValue> inputsOf(@NotNull IrStmt statement) {
		return switch (statement) {
			case IrOp op -> op.inputs();
			case IrEffect effect -> effect.inputs();
			case IrTerminator terminator -> terminator.inputs();
		};
	}

	private static boolean isReference(@NotNull IrValue value) {
		return ConversionSupport.isReferenceType(value.type());
	}

	private static boolean contains(@NotNull IrExceptionRegion outer, @NotNull IrExceptionRegion inner) {
		return outer != inner && inner.startOffset() >= outer.startOffset()
				&& inner.endOffset() <= outer.endOffset();
	}

	private static boolean overlaps(@NotNull IrExceptionRegion first, @NotNull IrExceptionRegion second) {
		return first.startOffset() < second.endOffset() && second.startOffset() < first.endOffset()
				&& !contains(first, second) && !contains(second, first);
	}

	private static boolean isClose(@NotNull IrOp op) {
		return op.payload() instanceof InvokeInstruction invoke && "close".equals(invoke.name());
	}

	private static @Nullable IrBlock gotoTarget(@NotNull IrBlock block,
	                                             @Nullable IrTerminator terminator,
	                                             @NotNull Map<Integer, IrBlock> blockByOffset) {
		if (terminator == null || terminator.kind() != IrTerminatorKind.GOTO) return null;
		if (terminator.payload() instanceof GotoInstruction gotoInstruction)
			return blockByOffset.get(gotoInstruction.jump().position());
		return block.successors().isEmpty() ? null : block.successors().getFirst();
	}
}
