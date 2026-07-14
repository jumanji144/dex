package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrExceptionValue;
import me.darknet.dex.convert.ir.value.IrParameter;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.UnaryInstruction;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages JVM local variable allocation for SSA values in an IR method.
 * <p>
 * Each value is assigned a unique local variable index, taking into account
 * the preferred register for certain values and the size of the value's type.
 */
final class JvmLocalAllocator {
	private final IrMethod method;
	private final Map<IrValue, JvmLiveness.Interval> intervals;
	private final Set<IrValue> loopSensitiveValues;
	private final List<ActiveSlot> active = new ArrayList<>();
	private final List<FreeSlot> free = new ArrayList<>();
	private final boolean preserveValueIdentity;
	private final boolean preserveAllValueIdentity;
	private int nextLocal;

	private JvmLocalAllocator(@NotNull IrMethod method, int firstLocal, boolean preserveValueIdentity,
	                         boolean preserveAllValueIdentity) {
		this.method = method;
		this.intervals = JvmLiveness.analyze(method);
		this.preserveValueIdentity = preserveValueIdentity;
		this.preserveAllValueIdentity = preserveAllValueIdentity;
		this.loopSensitiveValues = preserveValueIdentity ? collectLoopSensitiveValues() : Collections.emptySet();
		nextLocal = firstLocal;
	}

	static int allocate(@NotNull IrMethod method, int firstLocal) {
		return allocate(method, firstLocal, false);
	}

	static int allocate(@NotNull IrMethod method, int firstLocal, boolean preserveValueIdentity) {
		return allocate(method, firstLocal, preserveValueIdentity, false);
	}

	/**
	 * Allocates with stable source-value identity.  This is intentionally an
	 * aggressive-only presentation mode: verifier-safe interval reuse is still
	 * valid bytecode, but reusing a slot for unrelated SSA values makes
	 * decompilers reconstruct one mutable variable with many unrelated roles.
	 */
	static int allocate(@NotNull IrMethod method, int firstLocal, boolean preserveValueIdentity,
	                    boolean preserveAllValueIdentity) {
		return new JvmLocalAllocator(method, firstLocal, preserveValueIdentity,
				preserveAllValueIdentity).allocateValues();
	}

	private int allocateValues() {
		assignParameterLocals();
		List<IrValue> values = collectValues();
		values.sort(Comparator
				.comparingInt((IrValue value) -> intervalStart(value))
				.thenComparingInt(IrValue::id));
		for (IrValue value : values) allocateValue(value);
		return nextLocal;
	}

	private @NotNull List<IrValue> collectValues() {
		Set<IrValue> values = Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrBlock block : method.blocks()) {
			if (block.exceptionValue() != null) values.add(block.exceptionValue().canonical());
			for (IrPhi phi : block.phis())
				if (phi.canonical() == phi) values.add(phi);
			for (IrStmt statement : block.statements()) {
				if (statement instanceof IrOp op && op.canonical() == op
						&& !ConversionSupport.isVoidType(op.type())) values.add(op);
			}
		}
		return new ArrayList<>(values);
	}

	private void allocateValue(@NotNull IrValue value) {
		IrValue canonical = value.canonical();
		if (canonical != value || value instanceof IrConstant || value instanceof IrUnknown
				|| value instanceof IrParameter) return;
		JvmLiveness.Interval interval = intervals.get(value);
		if (interval == null) return;
		expire(interval.start());
		IrValue loopSource = loopIncrementSource(value);
		if (loopSource == null && preserveValueIdentity)
			loopSource = loopLongIncrementSource(value);
		if (loopSource != null && loopSource.hasLocal()) {
			// The header phi is consumed before the body increment. Its backedge
			// operand is exactly this result, so sharing the slot is equivalent to
			// the source-level loop counter update.
			removeActive(loopSource);
			value.local(loopSource.local());
			int size = ConversionSupport.slotSize(value.type());
			char category = localCategory(value.type());
			ClassType referenceType = ConversionSupport.isReferenceType(value.type()) ? value.type() : null;
			active.add(new ActiveSlot(value, interval.end(), value.local(), size, category, referenceType));
			return;
		}
		int size = ConversionSupport.slotSize(value.type());
		char category = localCategory(value.type());
		ClassType referenceType = ConversionSupport.isReferenceType(value.type()) ? value.type() : null;
		boolean preserveIdentity = preserveAllValueIdentity
				|| preserveValueIdentity && loopSensitiveValues.contains(value);
		FreeSlot selected = preserveIdentity ? null : takeFreeSlot(size, category, referenceType);
		int local;
		if (selected != null) {
			local = selected.local();
		} else {
			local = nextLocal;
			nextLocal += size;
		}
		value.local(local);
		active.add(new ActiveSlot(value, interval.end(), local, size, category, referenceType));
	}

	private @NotNull Set<IrValue> collectLoopSensitiveValues() {
		Set<IrBlock> loopBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrBlock block : method.blocks()) {
			for (IrBlock successor : block.successors()) {
				if (canReach(successor, block)) {
					loopBlocks.add(block);
					loopBlocks.add(successor);
				}
			}
		}
		Set<IrValue> values = Collections.newSetFromMap(new IdentityHashMap<>());
		for (IrBlock block : loopBlocks) {
			if (block.exceptionValue() != null) values.add(block.exceptionValue().canonical());
			for (IrPhi phi : block.phis()) {
				values.add(phi.canonical());
				phi.operands().values().forEach(value -> values.add(value.canonical()));
			}
			for (IrStmt statement : block.statements()) {
				switch (statement) {
					case IrOp op -> {
						values.add(op.canonical());
						op.inputs().forEach(value -> values.add(value.canonical()));
					}
					case IrEffect effect -> effect.inputs().forEach(value -> values.add(value.canonical()));
					case IrTerminator terminator -> terminator.inputs().forEach(value -> values.add(value.canonical()));
				}
			}
			if (block.terminator() != null)
				block.terminator().inputs().forEach(value -> values.add(value.canonical()));
		}
		return values;
	}

	private boolean canReach(@NotNull IrBlock from, @NotNull IrBlock target) {
		Set<IrBlock> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayList<IrBlock> work = new ArrayList<>();
		work.add(from);
		while (!work.isEmpty()) {
			IrBlock block = work.removeLast();
			if (block == target) return true;
			if (!visited.add(block)) continue;
			work.addAll(block.successors());
		}
		return false;
	}

	private @Nullable IrValue loopIncrementSource(@NotNull IrValue value) {
		if (!(value instanceof IrOp op)
				|| !(op.payload() instanceof BinaryLiteralInstruction instruction)
				|| (instruction.opcode() != Opcodes.ADD_INT_LIT8
				&& instruction.opcode() != Opcodes.ADD_INT_LIT16)
				|| op.inputs().size() != 1 || ConversionSupport.slotSize(value.type()) != 1
				|| localCategory(value.type()) != 'I') return null;
		IrValue source = op.inputs().getFirst().canonical();
		if (!(source instanceof IrPhi phi) || ConversionSupport.slotSize(source.type()) != 1
				|| localCategory(source.type()) != 'I') return null;
		for (IrBlock body : method.blocks()) {
			if (!body.statements().contains(op) || body.statements().getLast() != op
					|| body.exceptionValue() != null || !body.exceptionalSuccessors().isEmpty()
					|| body.successors().size() != 1) continue;
			if (!body.successors().getFirst().phis().contains(phi)) continue;
			IrBlock header = body.successors().getFirst();
			if (!header.predecessors().contains(body) || phi.operands().get(body) == null
					|| phi.operands().get(body).canonical() != op
					|| !noUsesAfter(source, op, body) || !onlyBackedgeUse(op, phi, body)) continue;
			return phi;
		}
		return null;
	}

	private @Nullable IrValue loopLongIncrementSource(@NotNull IrValue value) {
		if (!(value instanceof IrOp op)
				|| !(op.payload() instanceof BinaryInstruction instruction)
				|| instruction.opcode() != Opcodes.ADD_LONG
				|| op.inputs().size() != 2
				|| !(op.inputs().getFirst().canonical() instanceof IrPhi phi)
				|| !(op.inputs().get(1).canonical() instanceof IrOp conversion)
				|| !(conversion.payload() instanceof UnaryInstruction unary)
				|| unary.opcode() != Opcodes.INT_TO_LONG
				|| conversion.inputs().size() != 1
				|| !op.semantics().complete() || !conversion.semantics().complete()
				|| op.irType().kind() != me.darknet.dex.convert.ir.value.IrTypeKind.LONG
				|| conversion.irType().kind() != me.darknet.dex.convert.ir.value.IrTypeKind.LONG
				|| conversion.inputs().getFirst().canonical().irType().kind()
					!= me.darknet.dex.convert.ir.value.IrTypeKind.INT
				|| !ConversionSupport.isLongType(op.type()) || !ConversionSupport.isLongType(phi.type())
				|| !ConversionSupport.isLongType(conversion.type())) return null;
		for (IrBlock body : method.blocks()) {
			int operationIndex = body.statements().indexOf(op);
			int conversionIndex = body.statements().indexOf(conversion);
			if (operationIndex < 1 || conversionIndex != operationIndex - 1
					|| body.exceptionValue() != null || !body.exceptionalSuccessors().isEmpty()
					|| body.successors().size() != 1) continue;
			if (body.statements().get(conversionIndex) != conversion
					|| !body.successors().getFirst().phis().contains(phi)) continue;
			IrBlock header = body.successors().getFirst();
			if (!header.predecessors().contains(body) || phi.operands().get(body) == null
					|| phi.operands().get(body).canonical() != op || !noUsesAfter(phi, op, body)
					|| !onlyBackedgeUse(op, phi, body)) continue;
			return phi;
		}
		return null;
	}

	private boolean noUsesAfter(@NotNull IrValue source, @NotNull IrOp increment, @NotNull IrBlock body) {
		IrValue canonical = source.canonical();
		for (IrBlock block : method.blocks()) {
			if (block.index() > body.index()) {
				for (IrPhi phi : block.phis())
					for (IrValue operand : phi.operands().values())
						if (operand.canonical() == canonical) return false;
				for (IrStmt statement : block.statements())
					if (statementInputsContain(statement, canonical)) return false;
				if (block.terminator() != null && block.terminator().inputs().stream()
						.anyMatch(input -> input.canonical() == canonical)) return false;
			}
			if (block != body) continue;
			int incrementIndex = block.statements().indexOf(increment);
			for (int index = incrementIndex + 1; index < block.statements().size(); index++)
				if (statementInputsContain(block.statements().get(index), canonical)) return false;
			if (block.terminator() != null && block.terminator().inputs().stream()
					.anyMatch(input -> input.canonical() == canonical)) return false;
		}
		return true;
	}

	private boolean onlyBackedgeUse(@NotNull IrOp increment, @NotNull IrPhi phi, @NotNull IrBlock body) {
		for (IrBlock block : method.blocks()) {
			for (IrPhi candidate : block.phis()) {
				for (Map.Entry<IrBlock, IrValue> entry : candidate.operands().entrySet()) {
					if (entry.getValue().canonical() != increment) continue;
					if (candidate != phi || entry.getKey() != body) return false;
				}
			}
			for (IrStmt statement : block.statements())
				if (statementInputsContain(statement, increment)) return false;
			if (block.terminator() != null && block.terminator().inputs().stream()
					.anyMatch(input -> input.canonical() == increment)) return false;
		}
		return true;
	}

	private static boolean statementInputsContain(@NotNull IrStmt statement, @NotNull IrValue value) {
		return switch (statement) {
			case IrOp op -> op.inputs().stream().anyMatch(input -> input.canonical() == value);
			case IrEffect effect -> effect.inputs().stream().anyMatch(input -> input.canonical() == value);
			case IrTerminator terminator -> terminator.inputs().stream().anyMatch(input -> input.canonical() == value);
		};
	}

	private void removeActive(@NotNull IrValue value) {
		for (int i = active.size() - 1; i >= 0; i--)
			if (active.get(i).value() == value) active.remove(i);
	}


	private void expire(int start) {
		for (int i = active.size() - 1; i >= 0; i--) {
			ActiveSlot slot = active.get(i);
			if (slot.end() < start) {
				free.add(new FreeSlot(slot.local(), slot.size(), slot.category(), slot.referenceType()));
				active.remove(i);
			}
		}
	}

	private @Nullable FreeSlot takeFreeSlot(int size, char category, @Nullable ClassType referenceType) {
		for (int i = 0; i < free.size(); i++) {
			FreeSlot slot = free.get(i);
			if (slot.size() == size && slot.category() == category
					&& java.util.Objects.equals(slot.referenceType(), referenceType)) {
				free.remove(i);
				return slot;
			}
		}
		return null;
	}

	private int intervalStart(@NotNull IrValue value) {
		JvmLiveness.Interval interval = intervals.get(value);
		return interval == null ? Integer.MAX_VALUE : interval.start();
	}

	private static char localCategory(@NotNull ClassType type) {
		if (ConversionSupport.isReferenceType(type)) return 'A';
		if (ConversionSupport.isFloatType(type)) return 'F';
		if (ConversionSupport.isWideType(type)) return type.equals(Types.DOUBLE) ? 'D' : 'J';
		return 'I';
	}

	private void assignParameterLocals() {
		Map<Integer, IrParameter> parameters = new HashMap<>();
		for (IrParameter parameter : collectParameters()) parameters.put(parameter.register(), parameter);
		int sourceLocal = 0;
		int dexRegister = method.source().getCode().getRegisters() - method.source().getCode().getIn();
		if ((method.source().getAccess() & org.objectweb.asm.Opcodes.ACC_STATIC) == 0) {
			IrParameter parameter = parameters.get(dexRegister++);
			if (parameter != null) parameter.local(sourceLocal);
			sourceLocal++;
		}
		for (ClassType parameterType : method.source().getType().parameterTypes()) {
			IrParameter parameter = parameters.get(dexRegister);
			if (parameter != null) parameter.local(sourceLocal);
			sourceLocal += ConversionSupport.slotSize(parameterType);
			dexRegister += ConversionSupport.slotSize(parameterType);
		}
	}

	private @NotNull List<IrParameter> collectParameters() {
		Map<Integer, IrParameter> parameters = new HashMap<>();
		for (IrBlock block : method.blocks()) {
			for (IrPhi phi : block.phis())
				phi.operands().values().forEach(value -> collectParameter(parameters, value));
			for (IrStmt statement : block.statements()) {
				switch (statement) {
					case IrOp op -> op.inputs().forEach(value -> collectParameter(parameters, value));
					case IrEffect effect -> effect.inputs().forEach(value -> collectParameter(parameters, value));
					case IrTerminator ignored -> {
					}
				}
			}
			if (block.terminator() != null)
				block.terminator().inputs().forEach(value -> collectParameter(parameters, value));
		}
		return parameters.values().stream().sorted(Comparator.comparingInt(IrParameter::register)).toList();
	}

	private static void collectParameter(@NotNull Map<Integer, IrParameter> parameters, @NotNull IrValue value) {
		IrValue canonical = value.canonical();
		if (canonical instanceof IrParameter parameter) parameters.put(parameter.register(), parameter);
	}

	private record ActiveSlot(@NotNull IrValue value, int end, int local, int size, char category,
	                          @Nullable ClassType referenceType) {
	}

	private record FreeSlot(int local, int size, char category, @Nullable ClassType referenceType) {
	}
}
