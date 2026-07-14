package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.convert.ConversionDiagnostic;
import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrType;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.convert.ir.value.IrNullability;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Widening/narrowing inference over the source-neutral IR type lattice. */
public final class IrTypeAnalysis {
	public record Result(@NotNull List<ConversionDiagnostic> diagnostics,
	                     @NotNull Map<IrBlock, Map<IrValue, IrType>> flowFacts) {
		public Result {
			diagnostics = List.copyOf(diagnostics);
			Map<IrBlock, Map<IrValue, IrType>> copy = new IdentityHashMap<>();
			flowFacts.forEach((block, facts) -> copy.put(block, Map.copyOf(facts)));
			flowFacts = Collections.unmodifiableMap(copy);
		}
	}

	private IrTypeAnalysis() {}

	/** Compatibility entry point for IR-only callers. */
	public static void analyze(@NotNull List<IrBlock> blocks) {
		analyzeBlocks(blocks, null);
	}

	public static @NotNull Result analyze(@NotNull IrMethod method) {
		return analyze(method, IrTypeResolver.EMPTY);
	}

	public static @NotNull Result analyze(@NotNull IrMethod method, @NotNull IrTypeResolver resolver) {
		AnalysisOutput output = analyzeBlocks(method.blocks(), method, resolver);
		return new Result(output.diagnostics(), output.flowFacts());
	}

	private static void analyzeBlocks(List<IrBlock> blocks, IrMethod method) {
		analyzeBlocks(blocks, method, IrTypeResolver.EMPTY);
	}

	private static AnalysisOutput analyzeBlocks(List<IrBlock> blocks, IrMethod method,
	                                           IrTypeResolver resolver) {
		Set<IrValue> values = Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrBlock block : blocks) {
			values.addAll(block.phis());
			values.addAll(block.exceptionInputs().values());
			for (IrValue[] state : block.exceptionalExitStates().values()) if (state != null) Collections.addAll(values, state);
			for (IrStmt statement : block.statements()) {
				if (statement instanceof IrOp op) { values.add(op); values.addAll(op.inputs()); }
				if (statement instanceof IrEffect effect) values.addAll(effect.inputs());
			}
			if (block.terminator() != null) values.addAll(block.terminator().inputs());
			for (IrPhi phi : block.phis()) values.addAll(phi.operands().values());
		}
		values.remove(null);

		List<ConversionDiagnostic> diagnostics = new ArrayList<>();
		Set<String> reported = new LinkedHashSet<>();
		for (int iteration = 0; iteration < 64; iteration++) {
			boolean changed = false;
			for (IrBlock block : blocks) {
				for (IrPhi phi : block.phis()) {
					IrType joined = new IrType(IrTypeKind.BOTTOM, null, me.darknet.dex.convert.ir.value.IrNullability.UNKNOWN);
					boolean hasInput = false;
					for (IrValue input : phi.operands().values()) {
						IrValue canonical = input == null ? null : input.canonical();
						if (canonical == null || canonical == phi) continue;
						hasInput = true;
						joined = joinTypes(joined, IrType.from(canonical), resolver, diagnostics, reported, method,
								block.startOffset());
					}
					if (!hasInput) continue;
					if (joined.kind() == IrTypeKind.TOP) report(diagnostics, reported, method, block.startOffset(),
							"Conflicting phi inputs at block " + block.debugName());
					changed |= update(phi, joined);
				}
				for (IrStmt statement : block.statements()) {
					if (statement instanceof IrOp op) {
						IrInstructionSemantics semantics = op.semantics();
						changed |= constrain(op, semantics.inputs(), diagnostics, reported, method, resolver);
						changed |= update(op, joinTypes(op.irType(), semantics.result(), resolver, diagnostics, reported,
								method, instructionOffset(op, method)));
					} else if (statement instanceof IrEffect effect) {
						changed |= constrain(effect.inputs(), effect.semantics().inputs(), diagnostics, reported, method,
								block.startOffset(), resolver);
					}
				}
				IrTerminator terminator = block.terminator();
				if (terminator != null) {
					if (!terminator.semantics().complete())
						report(diagnostics, reported, method, block.startOffset(),
							"Incomplete semantic contract for terminator " + terminator.kind());
					changed |= constrain(terminator.inputs(), terminator.semantics().inputs(), diagnostics, reported,
							method, block.startOffset(), resolver);
				}
			}
			if (!changed) break;
		}

		// Narrow only values whose widened category is still compatible with an
		// exact materialized type.  This deliberately never mutates phis to fit a use.
		for (IrValue value : values) {
			IrType type = value.irType();
			if (type.kind() == IrTypeKind.TOP)
				report(diagnostics, reported, method, valueOffset(value, method), "Unresolved type constraint for value " + value.id());
			if (type.kind() == IrTypeKind.REFERENCE && type.exactReference() == null && value instanceof IrPhi)
				value.type(Types.OBJECT);
			value.irType(type);
		}
		diagnostics.sort(java.util.Comparator.comparingInt(ConversionDiagnostic::dexOffset)
				.thenComparing(d -> d.kind().name()).thenComparing(ConversionDiagnostic::message));
		return new AnalysisOutput(diagnostics, flowFacts(blocks, resolver));
	}

	private record AnalysisOutput(List<ConversionDiagnostic> diagnostics,
	                              Map<IrBlock, Map<IrValue, IrType>> flowFacts) {}

	private static @NotNull Map<IrBlock, Map<IrValue, IrType>> flowFacts(List<IrBlock> blocks,
	                                                                     IrTypeResolver resolver) {
		Map<IrBlock, Map<IrValue, IrType>> facts = new IdentityHashMap<>();
		for (IrBlock block : blocks) facts.put(block, new IdentityHashMap<>());
		for (int iteration = 0; iteration < 32; iteration++) {
			boolean changed = false;
			for (IrBlock block : blocks) {
				Map<IrValue, IrType> next = new IdentityHashMap<>();
				if (block.exceptionValue() != null)
					next.put(block.exceptionValue(), IrType.from(block.exceptionValue().type()));
				for (IrBlock predecessor : block.predecessors()) {
					Map<IrValue, IrType> predecessorFacts = facts.get(predecessor);
					boolean exceptional = block.exceptionInputs().containsKey(predecessor)
							|| predecessor.exceptionEdges().stream().anyMatch(edge -> edge.handlerBlock() == block);
					if (exceptional) {
						addExceptionalFact(predecessor, block, next, resolver);
					} else {
						if (predecessorFacts != null) mergeFacts(next, predecessorFacts, resolver);
						addBranchFact(predecessor, block, next, resolver);
					}
				}
				if (!next.equals(facts.get(block))) {
					facts.put(block, next);
					changed = true;
				}
			}
			if (!changed) break;
		}
		return facts;
	}

	private static void addExceptionalFact(IrBlock predecessor, IrBlock handler,
	                                      Map<IrValue, IrType> facts, IrTypeResolver resolver) {
		IrType caught = null;
		for (IrExceptionEdge edge : predecessor.exceptionEdges()) {
			if (edge.handlerBlock() != handler) continue;
			ClassType exceptionType = edge.handler() == null || edge.handler().exceptionType() == null
					? Types.instanceType(Throwable.class) : edge.handler().exceptionType();
			IrType edgeType = IrType.from(exceptionType);
			caught = caught == null ? edgeType : IrType.join(caught, edgeType, resolver);
		}
		if (caught == null) caught = IrType.from(Types.instanceType(Throwable.class));
		if (handler.exceptionValue() != null) {
			IrValue exception = handler.exceptionValue().canonical();
			IrType previous = facts.get(exception);
			facts.put(exception, previous == null ? caught : IrType.join(previous, caught, resolver));
		}
	}

	private static void mergeFacts(Map<IrValue, IrType> target, Map<IrValue, IrType> source,
	                               IrTypeResolver resolver) {
		for (Map.Entry<IrValue, IrType> entry : source.entrySet()) {
			IrValue value = entry.getKey().canonical();
			IrType previous = target.get(value);
			target.put(value, previous == null ? entry.getValue() : IrType.join(previous, entry.getValue(), resolver));
		}
	}

	private static void addBranchFact(IrBlock predecessor, IrBlock successor,
	                                  Map<IrValue, IrType> facts, IrTypeResolver resolver) {
		IrTerminator terminator = predecessor.terminator();
		if (terminator == null || terminator.inputs().isEmpty()) return;
		boolean taken = isTakenBranch(predecessor, successor, terminator);
		IrValue tested = terminator.inputs().getFirst().canonical();
		IrType fact = null;
		if (terminator.payload() instanceof BranchZeroInstruction branch
				&& (branch.opcode() == Opcodes.IF_EQZ || branch.opcode() == Opcodes.IF_NEZ)
				&& tested.irType().kind() == IrTypeKind.REFERENCE) {
			boolean nullBranch = branch.opcode() == Opcodes.IF_EQZ;
			if (!taken) nullBranch = !nullBranch;
			fact = new IrType(IrTypeKind.REFERENCE,
					nullBranch ? null : tested.irType().exactReference(),
					nullBranch ? IrNullability.NULL : IrNullability.NOT_NULL);
		} else if (terminator.payload() instanceof BranchInstruction branch
				&& (branch.opcode() == Opcodes.IF_EQ || branch.opcode() == Opcodes.IF_NE)
				&& terminator.inputs().size() > 1) {
			IrValue other = terminator.inputs().get(1).canonical();
			IrValue nullValue = tested.isZeroConstant() && tested.type() instanceof ReferenceType ? tested :
					other.isZeroConstant() && other.type() instanceof ReferenceType ? other : null;
			if (nullValue != null) {
				boolean nullBranch = branch.opcode() == Opcodes.IF_EQ;
				if (!taken) nullBranch = !nullBranch;
				IrValue value = nullValue == tested ? other : tested;
				fact = new IrType(IrTypeKind.REFERENCE,
						nullBranch ? null : value.irType().exactReference(),
						nullBranch ? IrNullability.NULL : IrNullability.NOT_NULL);
				tested = value;
			}
		} else if (tested instanceof IrOp op && op.payload() instanceof InstanceOfInstruction instruction
				&& terminator.payload() instanceof BranchZeroInstruction branch
				&& (branch.opcode() == Opcodes.IF_NEZ || branch.opcode() == Opcodes.IF_EQZ)) {
			boolean positive = branch.opcode() == Opcodes.IF_NEZ && taken
					|| branch.opcode() == Opcodes.IF_EQZ && !taken;
			if (positive && !op.inputs().isEmpty()) {
				fact = new IrType(IrTypeKind.REFERENCE, instruction.type(), IrNullability.NOT_NULL);
				tested = op.inputs().getFirst().canonical();
			}
		}
		if (fact != null) {
			IrType previous = facts.get(tested);
			facts.put(tested, previous == null ? fact : IrType.join(previous, fact, resolver));
		}
	}

	private static boolean isTakenBranch(IrBlock predecessor, IrBlock successor, IrTerminator terminator) {
		if (terminator.payload() instanceof BranchZeroInstruction branch)
			return successor.startOffset() == branch.label().position();
		if (terminator.payload() instanceof BranchInstruction branch)
			return successor.startOffset() == branch.label().position();
		return false;
	}

	private static boolean constrain(IrValue owner, List<IrTypeConstraint> constraints,
				List<ConversionDiagnostic> diagnostics, Set<String> reported, IrMethod method,
				IrTypeResolver resolver) {
		return constrain(owner instanceof IrOp op ? op.inputs() : List.of(), constraints, diagnostics, reported, method,
				owner.id(), resolver);
	}

	private static boolean constrain(List<IrValue> values, List<IrTypeConstraint> constraints,
				List<ConversionDiagnostic> diagnostics, Set<String> reported, IrMethod method, int offset,
				IrTypeResolver resolver) {
		boolean changed = false;
		for (int i = 0; i < Math.min(values.size(), constraints.size()); i++)
			changed |= constrainOne(values.get(i).canonical(), constraints.get(i), diagnostics, reported, method, offset, resolver);
		return changed;
	}

	private static boolean constrainOne(IrValue value, IrTypeConstraint constraint,
				List<ConversionDiagnostic> diagnostics, Set<String> reported, IrMethod method, int offset,
				IrTypeResolver resolver) {
		IrType current = IrType.from(value);
		IrType expected = constraint.expected();
		if (incompatible(current, expected))
			report(diagnostics, reported, method, offset,
				"Value " + value.id() + " used as " + constraint.role() + " with incompatible type " + current.kind());
		IrType joined = current;
		if (!(current.exactReference() instanceof ReferenceType currentReference)
				|| !(expected.exactReference() instanceof ReferenceType expectedReference)
				|| !IrTypeHierarchy.isAssignable(currentReference, expectedReference, resolver))
			joined = joinTypes(current, expected, resolver, diagnostics, reported, method, offset);
		if (value instanceof me.darknet.dex.convert.ir.value.IrUnknown)
			return false;
		if (joined.equals(value.irType())) return false;
		value.irType(joined);
		return true;
	}

	private static @NotNull IrType joinTypes(@NotNull IrType left, @NotNull IrType right,
	                                         @NotNull IrTypeResolver resolver,
	                                         @NotNull List<ConversionDiagnostic> diagnostics,
	                                         @NotNull Set<String> reported, @Nullable IrMethod method, int offset) {
		IrType joined = IrType.join(left, right, resolver);
		if (left.exactReference() instanceof ReferenceType leftReference
				&& right.exactReference() instanceof ReferenceType rightReference
				&& !leftReference.equals(rightReference)
				&& Types.OBJECT.equals(joined.exactReference())
				&& !Types.OBJECT.equals(leftReference) && !Types.OBJECT.equals(rightReference)
				&& (resolver.describe(leftReference) == null || resolver.describe(rightReference) == null)) {
			report(diagnostics, reported, method, offset,
					"Reference join widened to Object because hierarchy metadata is unavailable for "
							+ leftReference.descriptor() + " and " + rightReference.descriptor());
		}
		return joined;
	}

	private static int instructionOffset(@NotNull IrOp op, @Nullable IrMethod method) {
		return op.payload() instanceof Instruction instruction && method != null && method.source().getCode() != null
				? method.source().getCode().offsetOf(instruction) == null ? op.id()
				: method.source().getCode().offsetOf(instruction) : op.id();
	}

	private static int valueOffset(@NotNull IrValue value, @Nullable IrMethod method) {
		return value instanceof IrOp op ? instructionOffset(op, method) : value.id();
	}

	private static boolean update(IrValue value, IrType next) {
		if (next.equals(value.irType())) return false;
		value.irType(next);
		if (value instanceof IrPhi)
			// Do not retain a previously precise JVM category after a phi widens.
			value.type(next.materializedType());
		value.irType(next);
		return true;
	}

	private static boolean incompatible(IrType current, IrType expected) {
		if (current.kind() == IrTypeKind.BOTTOM || current.kind() == IrTypeKind.UNKNOWN
				|| expected.kind() == IrTypeKind.UNKNOWN || current.kind() == IrTypeKind.TOP) return false;
		if (current.kind() == expected.kind()) return false;
		return current.kind() != IrTypeKind.REFERENCE || expected.kind() != IrTypeKind.REFERENCE;
	}

	private static void report(List<ConversionDiagnostic> diagnostics, Set<String> reported, IrMethod method,
				int offset, String message) {
		String className = method == null || method.source().getOwner() == null ? "<unknown>"
				: ConversionSupport.asmOwner(method.source().getOwner());
		String methodName = method == null ? "<unknown>" : method.source().toString();
		String key = className + '|' + methodName + '|' + offset + '|' + message;
		if (!reported.add(key)) return;
		diagnostics.add(new ConversionDiagnostic(className, methodName, offset,
				ConversionDiagnostic.Severity.WARNING, ConversionDiagnostic.Kind.TYPE_INFERENCE, message, null));
	}
}
