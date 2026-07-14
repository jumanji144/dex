package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.IrExceptionHandler;
import me.darknet.dex.convert.ir.IrExceptionRegion;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrEffectKind;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.MonitorInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Discovers explicit monitor regions without rewriting the immutable IR. */
final class JvmMonitorRegionPlanner {
	private JvmMonitorRegionPlanner() {
	}

	static @NotNull List<JvmMonitorRegionCandidate> discover(@NotNull IrMethod method) {
		List<MonitorPoint> points = new ArrayList<>();
		for (IrBlock block : method.blocks()) {
			for (IrStmt statement : block.statements()) {
				if (!(statement instanceof IrEffect effect)
						|| effect.kind() != IrEffectKind.MONITOR
						|| !(effect.payload() instanceof MonitorInstruction instruction)
						|| effect.inputs().isEmpty()) continue;
				points.add(new MonitorPoint(block, effect, effect.inputs().getFirst().canonical(),
						offset(method, effect.payload(), block)));
			}
		}
		points.sort(Comparator.comparingInt(MonitorPoint::offset)
				.thenComparingInt(point -> point.block().index()));

		List<JvmMonitorRegionCandidate> result = new ArrayList<>();
		for (MonitorPoint enter : points) {
			if (((MonitorInstruction) enter.effect().payload()).exit()) continue;
			List<MonitorPoint> exits = points.stream()
					.filter(point -> ((MonitorInstruction) point.effect().payload()).exit()
							&& point.lock() == enter.lock() && point.offset() > enter.offset())
					.toList();
			if (exits.isEmpty()) continue;

			List<IrBlock> exitBlocks = uniqueBlocks(exits);
			List<IrBlock> protectedBlocks = protectedBlocks(enter.block(), exitBlocks);
			List<IrExceptionEdge> exceptionalEdges = protectedBlocks.stream()
					.flatMap(block -> block.exceptionEdges().stream()).distinct().toList();
			List<IrBlock> normalExits = exitBlocks.stream()
					.filter(block -> block.exceptionValue() == null).toList();
			List<IrBlock> exceptionalExits = exitBlocks.stream()
					.filter(block -> block.exceptionValue() != null
							|| block.predecessors().stream().anyMatch(predecessor ->
									predecessor.exceptionalSuccessors().contains(block)))
					.toList();
			String signature = exits.isEmpty() ? "" : exitSignature(exits.getFirst().effect(), exits.getFirst().block());
			boolean sameSignature = exits.stream().allMatch(point ->
					signature.equals(exitSignature(point.effect(), point.block())));
			List<String> rangeProfile = rangeProfile(method, protectedBlocks);
			List<String> nestingProfile = nestingProfile(points, enter, exits);
			Proof proof = proof(method, enter, exits, exitBlocks, protectedBlocks, normalExits,
					exceptionalExits, sameSignature, nestingProfile);
			result.add(new JvmMonitorRegionCandidate(enter.lock(), enter.effect(),
					exits.stream().map(MonitorPoint::effect).toList(), enter.block(), protectedBlocks,
				normalExits, exceptionalExits, exceptionalEdges, rangeProfile, nestingProfile,
				enter.offset(), signature, proof.eligible(), proof.reason()));
		}
		return List.copyOf(result);
	}

	private static @NotNull Proof proof(@NotNull IrMethod method, @NotNull MonitorPoint enter,
	                                    @NotNull List<MonitorPoint> exits,
	                                    @NotNull List<IrBlock> exitBlocks,
	                                    @NotNull List<IrBlock> protectedBlocks,
	                                    @NotNull List<IrBlock> normalExits,
	                                    @NotNull List<IrBlock> exceptionalExits,
                                    boolean sameSignature,
                                    @NotNull List<String> nestingProfile) {
		if (exits.size() < 2) return new Proof(false, "monitor region has no duplicate cleanup exits");
		if (!ConversionSupport.isReferenceType(enter.lock().type())
				|| enter.lock().irType().kind() != IrTypeKind.REFERENCE)
			return new Proof(false, "monitor lock is not a proven reference");
		if (!materialized(enter.lock())) return new Proof(false, "monitor lock is not materialized");
		if (!enter.effect().semantics().complete()) return new Proof(false, "monitor-enter semantics are incomplete");
		if (!sameSignature) return new Proof(false, "monitor-exit terminal or semantic signatures differ");
		if (!method.exceptionRegions().stream().noneMatch(region -> region.protectedBlocks().contains(enter.block())))
			return new Proof(false, "monitor-enter is inside a protected range");
		if (nestingProfile.stream().anyMatch(value -> value.startsWith("enter:")))
			return new Proof(false, "nested monitor acquisition crosses the shared cleanup boundary");
		if (!pathsReachExit(enter.block(), Set.copyOf(exitBlocks), enter.effect()))
			return new Proof(false, "a normal completion can bypass monitor-exit");
		if (!normalExits.isEmpty() && !exceptionalExits.isEmpty()) {
			// A normal and an exceptional handler tail may share only after the
			// handler has materialized its exception state.  The exit blocks are
			// allowed here, but they must have no handler state of their own.
			if (exitBlocks.stream().anyMatch(block -> block.exceptionValue() != null))
				return new Proof(false, "monitor cleanup still carries handler state");
		}
		for (IrBlock block : exitBlocks) {
			if (!shareableExit(block, enter.lock()))
				return new Proof(false, "monitor-exit block has observable or exceptional state");
			if (!rangeProfile(method, List.of(block)).isEmpty())
				return new Proof(false, "monitor-exit crosses a protected-range boundary");
		}
		for (IrBlock block : protectedBlocks) {
			if (block.phis().stream().anyMatch(phi -> !materialized(phi)))
				return new Proof(false, "protected monitor state contains an unmaterialized phi");
			for (IrStmt statement : block.statements()) {
				if (!complete(statement)) return new Proof(false, "monitor body has incomplete semantics");
			}
		}
		return new Proof(true, "equivalent monitor exits have materialized lock and cleanup state");
	}

	private static boolean shareableExit(@NotNull IrBlock block, @NotNull IrValue lock) {
		if (!block.phis().isEmpty() || block.exceptionValue() != null || !block.exceptionalSuccessors().isEmpty())
			return false;
		List<IrEffect> effects = block.statements().stream().filter(IrEffect.class::isInstance)
				.map(IrEffect.class::cast).toList();
		if (effects.size() != 1 || block.statements().size() != 1) return false;
		IrEffect effect = effects.getFirst();
		return effect.kind() == IrEffectKind.MONITOR && effect.payload() instanceof MonitorInstruction instruction
				&& instruction.exit() && effect.inputs().size() == 1
				&& effect.inputs().getFirst().canonical() == lock
				&& effect.semantics().complete()
				&& block.terminator() != null
				&& block.terminator().kind() != IrTerminatorKind.IF
				&& block.terminator().kind() != IrTerminatorKind.IF_ZERO
				&& block.terminator().kind() != IrTerminatorKind.SWITCH;
	}

	private static boolean complete(@NotNull IrStmt statement) {
		return switch (statement) {
			case me.darknet.dex.convert.ir.statement.IrOp op -> op.semantics().complete();
			case IrEffect effect -> effect.semantics().complete();
			case me.darknet.dex.convert.ir.statement.IrTerminator terminator -> terminator.semantics().complete();
		};
	}

	private static boolean materialized(@NotNull IrValue value) {
		IrValue canonical = value.canonical();
		return !(canonical instanceof IrUnknown) && !canonical.isImprecise() && !canonical.stackOnly()
				&& (canonical instanceof IrConstant || canonical.hasLocal());
	}

	private static boolean pathsReachExit(@NotNull IrBlock start, @NotNull Set<IrBlock> exits,
	                                     @NotNull IrEffect enter) {
		ArrayDeque<IrBlock> work = new ArrayDeque<>();
		Set<IrBlock> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		work.add(start);
		while (!work.isEmpty()) {
			IrBlock block = work.removeFirst();
			if (!visited.add(block) || exits.contains(block)) continue;
			List<IrBlock> next = new ArrayList<>(block.successors());
			for (var edge : block.exceptionEdges()) {
				// monitor-enter may throw before ownership is acquired.  Its
				// exceptional transfer must not be forced through monitor-exit.
				if (edge.throwingInstruction() != enter.payload())
					next.add(edge.handlerBlock());
			}
			if (next.isEmpty()) return false;
			work.addAll(next);
		}
		return true;
	}

	private static @NotNull List<IrBlock> protectedBlocks(@NotNull IrBlock start,
	                                                       @NotNull List<IrBlock> exits) {
		Set<IrBlock> exitSet = Collections.newSetFromMap(new IdentityHashMap<>());
		exitSet.addAll(exits);
		Set<IrBlock> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<IrBlock> work = new ArrayDeque<>();
		List<IrBlock> result = new ArrayList<>();
		work.add(start);
		while (!work.isEmpty()) {
			IrBlock block = work.removeFirst();
			if (!visited.add(block)) continue;
			result.add(block);
			if (exitSet.contains(block)) continue;
			work.addAll(block.successors());
		}
		result.sort(Comparator.comparingInt(IrBlock::index));
		return List.copyOf(result);
	}

	private static @NotNull List<IrBlock> uniqueBlocks(@NotNull List<MonitorPoint> points) {
		Set<IrBlock> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<IrBlock> result = new ArrayList<>();
		for (MonitorPoint point : points)
			if (seen.add(point.block())) result.add(point.block());
		return List.copyOf(result);
	}

	private static @NotNull String exitSignature(@NotNull IrEffect effect, @NotNull IrBlock block) {
		var terminator = block.terminator();
		return effect.semantics().loweringId() + ":" + effect.semantics().throwMask() + ":"
				+ terminator.kind() + ":" + terminator.semantics().loweringId() + ":"
				+ terminator.semantics().throwMask();
	}

	private static @NotNull List<String> rangeProfile(@NotNull IrMethod method,
	                                                   @NotNull List<IrBlock> blocks) {
		List<String> result = new ArrayList<>();
		for (IrExceptionRegion region : method.exceptionRegions()) {
			if (blocks.stream().noneMatch(region.protectedBlocks()::contains)) continue;
			for (IrExceptionHandler handler : region.handlers()) {
				String type = handler.handler() == null || handler.handler().isCatchAll() ? "*"
						: handler.handler().exceptionType().descriptor();
				result.add(region.startOffset() + ":" + region.endOffset() + ":" + type);
			}
		}
		Collections.sort(result);
		return List.copyOf(result);
	}

	private static @NotNull List<String> nestingProfile(@NotNull List<MonitorPoint> points,
	                                                    @NotNull MonitorPoint enter,
	                                                    @NotNull List<MonitorPoint> exits) {
		int end = exits.stream().mapToInt(MonitorPoint::offset).max().orElse(enter.offset());
		return points.stream().filter(point -> point != enter && point.offset() > enter.offset()
				&& point.offset() < end)
			.map(point -> (point.effect().payload() instanceof MonitorInstruction instruction && instruction.exit() ? "exit:" : "enter:")
					+ point.lock().irType().kind() + ":" + point.offset())
			.sorted().toList();
	}

	private static int offset(@NotNull IrMethod method, @NotNull Object payload, @NotNull IrBlock block) {
		if (method.source().getCode() != null && payload instanceof Instruction instruction) {
			Integer offset = method.source().getCode().offsetOf(instruction);
			if (offset != null) return offset;
		}
		return block.startOffset();
	}

	private record MonitorPoint(@NotNull IrBlock block, @NotNull IrEffect effect,
	                            @NotNull IrValue lock, int offset) {
	}

	private record Proof(boolean eligible, @NotNull String reason) {
	}
}
