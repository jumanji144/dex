package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionRegion;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovers conservative natural-loop facts for aggressive JVM layout. */
final class JvmLoopShapePlanner {
	private JvmLoopShapePlanner() {}

	static @NotNull List<JvmLoopShapeCandidate> discover(@NotNull IrMethod method,
	                                                     @NotNull JvmOptimizationGuards guards) {
		List<JvmLoopShapeCandidate> result = new ArrayList<>();
		for (IrBlock source : method.blocks()) {
			for (IrBlock header : source.successors()) {
				if (!dominates(header, source, method)) continue;
				JvmLoopShapeCandidate candidate = candidate(method, guards, header, source);
				if (candidate != null) result.add(candidate);
			}
		}
		result.sort(Comparator.comparingInt(JvmLoopShapeCandidate::sourceOffset)
				.thenComparing(candidate -> candidate.kind().ordinal())
				.thenComparing(candidate -> candidate.header().index()));
		return List.copyOf(result);
	}

	private static JvmLoopShapeCandidate candidate(IrMethod method, JvmOptimizationGuards guards,
	                                                IrBlock header, IrBlock backedge) {
		Set<IrBlock> loop = naturalLoop(header, backedge);
		List<IrBlock> preheaders = header.predecessors().stream()
				.filter(predecessor -> !loop.contains(predecessor)).toList();
		List<IrBlock> exits = loop.stream().flatMap(block -> block.successors().stream())
				.filter(successor -> !loop.contains(successor)).distinct()
				.sorted(Comparator.comparingInt(IrBlock::index)).toList();
		if (preheaders.size() != 1 || exits.size() != 1) return null;
		List<IrBlock> predicates = loop.stream().filter(JvmLoopShapePlanner::isPredicate)
				.sorted(Comparator.comparingInt(IrBlock::index)).toList();
		List<IrPhi> phis = header.phis().stream().filter(phi -> loopCarried(phi, loop, header, backedge)).toList();
		List<IrValue> induction = new ArrayList<>();
		for (IrPhi phi : phis) {
			if (hasIntIncrement(loop, phi, backedge) || hasLongIncrement(loop, phi, backedge)) induction.add(phi);
		}
		boolean array = induction.stream().anyMatch(phi -> hasArrayAccess(loop, phi));
		boolean counted = !induction.isEmpty();
		boolean shortCircuit = predicates.size() >= 2 && predicates.stream()
				.allMatch(block -> hasExitTo(block, exits.getFirst(), loop));
		JvmLoopShapeKind kind;
		if (array) kind = JvmLoopShapeKind.ARRAY_INDEX;
		else if (counted) kind = JvmLoopShapeKind.COUNTED;
		else if (shortCircuit) kind = JvmLoopShapeKind.SHORT_CIRCUIT;
		else return null;

		String reason = proofReason(method, guards, loop, header, backedge, preheaders.getFirst(), exits.getFirst(), phis, induction);
		List<String> protectedProfile = protectedProfile(method, loop);
		List<String> exceptionalProfile = exceptionalProfile(loop);
		if (!protectedProfile.isEmpty() || !exceptionalProfile.isEmpty()) {
			// A layout plan may still be useful for diagnostics, but no block is
			// relocated across a protected or exceptional boundary in this slice.
			reason = "loop crosses protected or exceptional control-flow state";
		}
		return new JvmLoopShapeCandidate(kind, preheaders.getFirst(), header, predicates,
				loop.stream().sorted(Comparator.comparingInt(IrBlock::index)).toList(), backedge,
				exits.getFirst(), terminalExits(loop), phis, induction, header.startOffset(),
				protectedProfile, exceptionalProfile, reason == null, reason == null ? "proven natural loop" : reason);
	}

	private static String proofReason(IrMethod method, JvmOptimizationGuards guards, Set<IrBlock> loop,
	                                 IrBlock header, IrBlock backedge, IrBlock preheader, IrBlock exit,
	                                 List<IrPhi> phis, List<IrValue> induction) {
		if (header == backedge) return "self-loop has no distinct backedge block";
		if (header.terminator() == null || backedge.terminator() == null) return "loop has an incomplete terminator";
		if (header.phis().isEmpty() && !induction.isEmpty()) return "induction value has no header phi";
		for (IrBlock block : loop) {
			if (block.exceptionValue() != null || block.exceptionalSuccessors().stream().anyMatch(loop::contains))
				return "loop carries handler state or an exceptional backedge";
			if (block.terminator() == null || !guards.safeStatement(block.terminator()))
				return "loop terminator semantics are incomplete";
			for (IrStmt statement : block.statements()) {
				if (!guards.safeStatement(statement)) return "loop contains unknown or imprecise value";
			}
		}
		for (IrPhi phi : phis) {
			if (!phi.hasLocal() || phi.operands().get(preheader) == null || phi.operands().get(backedge) == null)
				return "loop phi lacks a materialized preheader/backedge state";
			if (!knownValue(phi) || phi.operands().values().stream().anyMatch(value -> !knownValue(value)))
				return "loop phi is imprecise";
		}
		for (IrValue value : induction)
			if (!value.hasLocal() || !knownValue(value) || value.irType().kind().isWide())
				return "induction local is not a stable JVM category";
		if (exit == preheader) return "loop exit aliases preheader";
		return null;
	}

	private static boolean knownValue(IrValue value) {
		IrValue canonical = value.canonical();
		IrTypeKind kind = canonical.irType().kind();
		return !(canonical instanceof IrUnknown) && !canonical.isImprecise()
				&& kind != IrTypeKind.UNKNOWN && kind != IrTypeKind.TOP && kind != IrTypeKind.BOTTOM;
	}

	private static boolean loopCarried(IrPhi phi, Set<IrBlock> loop, IrBlock header, IrBlock backedge) {
		return phi.block() == header && phi.operands().containsKey(backedge)
				&& phi.operands().keySet().stream().anyMatch(loop::contains);
	}

	private static boolean hasIntIncrement(Set<IrBlock> loop, IrPhi phi, IrBlock backedge) {
		for (IrBlock block : loop) {
			if (block != backedge || block.statements().isEmpty() || block.successors().size() != 1
					|| block.successors().getFirst() != phi.block()) continue;
			IrStmt statement = block.statements().getLast();
			if (!(statement instanceof IrOp op) || op.inputs().size() != 1
					|| op.inputs().getFirst().canonical() != phi
					|| !(op.payload() instanceof BinaryLiteralInstruction literal)
					|| (literal.opcode() != Opcodes.ADD_INT_LIT8 && literal.opcode() != Opcodes.ADD_INT_LIT16)) continue;
			if (phi.operands().get(block) != null && phi.operands().get(block).canonical() == op) return true;
		}
		return false;
	}

	private static boolean hasLongIncrement(Set<IrBlock> loop, IrPhi phi, IrBlock backedge) {
		for (IrBlock block : loop) {
			if (block != backedge || block.statements().size() < 2 || block.successors().size() != 1
					|| block.successors().getFirst() != phi.block()) continue;
			IrStmt statement = block.statements().getLast();
			if (!(statement instanceof IrOp op) || op.inputs().size() != 2
					|| op.inputs().getFirst().canonical() != phi
					|| !(op.payload() instanceof BinaryInstruction binary)
					|| binary.opcode() != Opcodes.ADD_LONG) continue;
			if (phi.operands().get(block) != null && phi.operands().get(block).canonical() == op) return true;
		}
		return false;
	}

	private static boolean hasArrayAccess(Set<IrBlock> loop, IrValue induction) {
		for (IrBlock block : loop) for (IrStmt statement : block.statements())
			if (statement instanceof IrOp op && op.payload() instanceof ArrayInstruction
					&& op.inputs().size() > 1 && op.inputs().get(1).canonical() == induction)
				return true;
		return false;
	}

	private static boolean hasExitTo(IrBlock block, IrBlock exit, Set<IrBlock> loop) {
		return block.successors().contains(exit) && block.successors().stream().anyMatch(loop::contains);
	}

	private static boolean isPredicate(IrBlock block) {
		IrTerminator terminator = block.terminator();
		return terminator != null && (terminator.kind() == IrTerminatorKind.IF || terminator.kind() == IrTerminatorKind.IF_ZERO);
	}

	private static List<IrBlock> terminalExits(Set<IrBlock> loop) {
		return loop.stream().filter(block -> block.terminator() != null
				&& (block.terminator().kind() == IrTerminatorKind.RETURN || block.terminator().kind() == IrTerminatorKind.THROW))
				.sorted(Comparator.comparingInt(IrBlock::index)).toList();
	}

	private static Set<IrBlock> naturalLoop(IrBlock header, IrBlock backedge) {
		Set<IrBlock> loop = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<IrBlock> work = new ArrayDeque<>();
		loop.add(header);
		loop.add(backedge);
		work.add(backedge);
		while (!work.isEmpty()) {
			IrBlock block = work.removeFirst();
			for (IrBlock predecessor : block.predecessors())
				if (predecessor != header && loop.add(predecessor)) work.addLast(predecessor);
		}
		return loop;
	}

	private static boolean dominates(IrBlock dominator, IrBlock block, IrMethod method) {
		if (dominator == block) return true;
		Set<IrBlock> reachable = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<IrBlock> work = new ArrayDeque<>();
		work.add(method.entry());
		while (!work.isEmpty()) {
			IrBlock current = work.removeFirst();
			if (!reachable.add(current)) continue;
			work.addAll(current.successors());
		}
		if (!reachable.contains(block)) return false;
		Set<IrBlock> without = Collections.newSetFromMap(new IdentityHashMap<>());
		work.clear();
		work.add(method.entry());
		while (!work.isEmpty()) {
			IrBlock current = work.removeFirst();
			if (current == dominator || !without.add(current)) continue;
			work.addAll(current.successors());
		}
		return !without.contains(block);
	}

	private static List<String> protectedProfile(IrMethod method, Set<IrBlock> loop) {
		return method.exceptionRegions().stream()
				.filter(region -> region.protectedBlocks().stream().anyMatch(loop::contains))
				.map(region -> region.startOffset() + ":" + region.endOffset())
				.sorted().toList();
	}

	private static List<String> exceptionalProfile(Set<IrBlock> loop) {
		return loop.stream().flatMap(block -> block.exceptionalSuccessors().stream()
				.map(target -> block.index() + "->" + target.index())).sorted().toList();
	}
}
