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
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
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
	private final Map<RegisterLocalKey, Integer> registerLocals = new HashMap<>();
	private int nextLocal;

	private JvmLocalAllocator(@NotNull IrMethod method, int firstLocal) {
		this.method = method;
		nextLocal = firstLocal;
	}

	static int allocate(@NotNull IrMethod method, int firstLocal) {
		return new JvmLocalAllocator(method, firstLocal).allocateValues();
	}

	private int allocateValues() {
		assignParameterLocals();
		for (IrBlock block : method.blocks()) {
			if (block.exceptionValue() != null) allocateValue(block.exceptionValue());
			for (IrPhi phi : block.phis())
				if (phi.canonical() == phi) allocateRegisterValue(phi, phi.register());
			for (IrStmt statement : block.statements()) {
				if (statement instanceof IrOp op && op.canonical() == op
						&& !ConversionSupport.isVoidType(op.type()) && !op.stackOnly())
					allocateValue(op);
			}
		}
		IrValue[] entry = method.entry().exitState();
		if (entry != null) {
			for (IrValue value : entry) {
				if (value instanceof IrParameter parameter) allocateValue(parameter);
			}
		}
		return nextLocal;
	}

	private void allocateValue(@NotNull IrValue value) {
		if (value instanceof IrConstant) return;
		Integer register = preferredRegister(value);
		if (register != null) {
			allocateRegisterValue(value, register);
			return;
		}
		if (value.hasLocal()) {
			nextLocal = Math.max(nextLocal, value.local() + ConversionSupport.slotSize(value.type()));
			return;
		}
		value.local(nextLocal);
		nextLocal += ConversionSupport.slotSize(value.type());
	}

	private void allocateRegisterValue(@NotNull IrValue value, int register) {
		RegisterLocalKey key = new RegisterLocalKey(register, localCategory(value.type()), referenceType(value.type()));
		Integer local = registerLocals.get(key);
		if (local == null && value.hasLocal()) {
			local = value.local();
			registerLocals.put(key, local);
		}
		if (local == null) {
			local = nextLocal;
			registerLocals.put(key, local);
			nextLocal += ConversionSupport.slotSize(value.type());
		} else {
			nextLocal = Math.max(nextLocal, local + ConversionSupport.slotSize(value.type()));
		}
		if (!value.hasLocal()) value.local(local);
	}

	private static char localCategory(@NotNull ClassType type) {
		if (ConversionSupport.isReferenceType(type)) return 'A';
		if (ConversionSupport.isFloatType(type)) return 'F';
		if (ConversionSupport.isWideType(type)) return type.equals(Types.DOUBLE) ? 'D' : 'J';
		return 'I';
	}

	private static @Nullable ClassType referenceType(@NotNull ClassType type) {
		return ConversionSupport.isReferenceType(type) ? type : null;
	}

	private static @Nullable Integer preferredRegister(@NotNull IrValue value) {
		if (value instanceof IrOp op && op.hasRegister()) return op.register();
		if (value instanceof IrExceptionValue exceptionValue && exceptionValue.hasRegister())
			return exceptionValue.register();
		return null;
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

	private record RegisterLocalKey(int register, char category, @Nullable ClassType referenceType) {
	}
}

