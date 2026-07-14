package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.ArrayLengthInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
import me.darknet.dex.tree.definitions.instructions.CompareInstruction;
import me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.UnaryInstruction;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Emits computational IR operations and manages their JVM result lifetime.
 * <p>
 * Value loading, local storage, and expression-inlining decisions are
 * supplied through {@link Host}.This keeps opcode mapping independent of
 * the surrounding block and exception layout.
 */
final class IrOperationEmitter {
	private final MethodVisitor mv;
	private final Host host;

	IrOperationEmitter(@NotNull MethodVisitor mv, @NotNull Host host) {
		this.mv = mv;
		this.host = host;
	}

	void emit(@NotNull IrOp op, @NotNull ResultMode resultMode) {
		switch (op.payload()) {
			case BinaryInstruction instruction -> emitBinary(op, instruction, resultMode);
			case BinaryLiteralInstruction instruction -> emitBinaryLiteral(op, instruction, resultMode);
			case UnaryInstruction instruction -> emitUnary(op, instruction, resultMode);
			case CompareInstruction instruction -> emitCompare(op, instruction, resultMode);
			case ArrayLengthInstruction ignored -> {
				loadInput(op, 0);
				mv.visitInsn(ARRAYLENGTH);
				finishValue(op, resultMode);
			}
			case CheckCastInstruction instruction -> {
				loadInput(op, 0);
				mv.visitTypeInsn(CHECKCAST, ConversionSupport.asmTypeOperand(instruction.type()));
				finishValue(op, resultMode);
			}
			case InstanceOfInstruction instruction -> {
				loadInput(op, 0);
				mv.visitTypeInsn(INSTANCEOF, ConversionSupport.asmTypeOperand(instruction.type()));
				finishValue(op, resultMode);
			}
			case NewInstanceInstruction instruction -> {
				mv.visitTypeInsn(NEW, instruction.type().internalName());
				finishValue(op, resultMode);
			}
			case NewArrayInstruction instruction -> {
				loadInput(op, 0);
				ConversionSupport.emitNewArray(mv, instruction.componentType());
				finishValue(op, resultMode);
			}
			case FilledNewArrayInstruction instruction -> emitFilledNewArray(op, instruction, resultMode);
			case ArrayInstruction instruction -> emitArrayGet(op, instruction, resultMode);
			case InstanceFieldInstruction instruction -> {
				loadInput(op, 0);
				mv.visitFieldInsn(GETFIELD, instruction.owner().internalName(), instruction.name(),
						instruction.type().descriptor());
				finishValue(op, resultMode);
			}
			case StaticFieldInstruction instruction -> {
				mv.visitFieldInsn(GETSTATIC, instruction.owner().internalName(), instruction.name(),
						instruction.type().descriptor());
				finishValue(op, resultMode);
			}
			case InvokeInstruction instruction -> emitInvoke(op, instruction, resultMode);
			case InvokeCustomInstruction instruction -> emitInvokeCustom(op, instruction, resultMode);
			default -> throw new IllegalStateException("Unsupported op payload: " + op.payload());
		}
	}

	private void emitBinary(@NotNull IrOp op, @NotNull BinaryInstruction instruction,
	                        @NotNull ResultMode resultMode) {
		if (host.tryEmitLongIncrement(op, resultMode)) return;
		loadInput(op, 0);
		loadInput(op, 1);
		mv.visitInsn(switch (instruction.opcode()) {
			case Opcodes.ADD_INT -> IADD;
			case Opcodes.SUB_INT -> ISUB;
			case Opcodes.MUL_INT -> IMUL;
			case Opcodes.DIV_INT -> IDIV;
			case Opcodes.REM_INT -> IREM;
			case Opcodes.AND_INT -> IAND;
			case Opcodes.OR_INT -> IOR;
			case Opcodes.XOR_INT -> IXOR;
			case Opcodes.SHL_INT -> ISHL;
			case Opcodes.SHR_INT -> ISHR;
			case Opcodes.USHR_INT -> IUSHR;
			case Opcodes.ADD_LONG -> LADD;
			case Opcodes.SUB_LONG -> LSUB;
			case Opcodes.MUL_LONG -> LMUL;
			case Opcodes.DIV_LONG -> LDIV;
			case Opcodes.REM_LONG -> LREM;
			case Opcodes.AND_LONG -> LAND;
			case Opcodes.OR_LONG -> LOR;
			case Opcodes.XOR_LONG -> LXOR;
			case Opcodes.SHL_LONG -> LSHL;
			case Opcodes.SHR_LONG -> LSHR;
			case Opcodes.USHR_LONG -> LUSHR;
			case Opcodes.ADD_FLOAT -> FADD;
			case Opcodes.SUB_FLOAT -> FSUB;
			case Opcodes.MUL_FLOAT -> FMUL;
			case Opcodes.DIV_FLOAT -> FDIV;
			case Opcodes.REM_FLOAT -> FREM;
			case Opcodes.ADD_DOUBLE -> DADD;
			case Opcodes.SUB_DOUBLE -> DSUB;
			case Opcodes.MUL_DOUBLE -> DMUL;
			case Opcodes.DIV_DOUBLE -> DDIV;
			case Opcodes.REM_DOUBLE -> DREM;
			default -> throw new IllegalArgumentException("Unsupported binary opcode: " + instruction.opcode());
		});
		finishValue(op, resultMode);
	}

	private void emitBinaryLiteral(@NotNull IrOp op, @NotNull BinaryLiteralInstruction instruction,
	                               @NotNull ResultMode resultMode) {
		if (host.tryEmitIncrement(op, instruction.constant(), resultMode)) return;
		loadInput(op, 0);
		ConversionSupport.pushInt(mv, instruction.constant());
		switch (instruction.opcode()) {
			case Opcodes.RSUB_INT, Opcodes.RSUB_INT_LIT8 -> {
				mv.visitInsn(SWAP);
				mv.visitInsn(ISUB);
			}
			case Opcodes.ADD_INT_LIT16, Opcodes.ADD_INT_LIT8 -> mv.visitInsn(IADD);
			case Opcodes.MUL_INT_LIT16, Opcodes.MUL_INT_LIT8 -> mv.visitInsn(IMUL);
			case Opcodes.DIV_INT_LIT16, Opcodes.DIV_INT_LIT8 -> mv.visitInsn(IDIV);
			case Opcodes.REM_INT_LIT16, Opcodes.REM_INT_LIT8 -> mv.visitInsn(IREM);
			case Opcodes.AND_INT_LIT16, Opcodes.AND_INT_LIT8 -> mv.visitInsn(IAND);
			case Opcodes.OR_INT_LIT16, Opcodes.OR_INT_LIT8 -> mv.visitInsn(IOR);
			case Opcodes.XOR_INT_LIT16, Opcodes.XOR_INT_LIT8 -> mv.visitInsn(IXOR);
			case Opcodes.SHL_INT_LIT8 -> mv.visitInsn(ISHL);
			case Opcodes.SHR_INT_LIT8 -> mv.visitInsn(ISHR);
			case Opcodes.USHR_INT_LIT8 -> mv.visitInsn(IUSHR);
			default -> throw new IllegalArgumentException("Unsupported binary literal opcode: " + instruction.opcode());
		}
		finishValue(op, resultMode);
	}

	private void emitUnary(@NotNull IrOp op, @NotNull UnaryInstruction instruction,
	                       @NotNull ResultMode resultMode) {
		loadInput(op, 0);
		mv.visitInsn(switch (instruction.opcode()) {
			case Opcodes.NEG_INT -> INEG;
			case Opcodes.NEG_LONG -> LNEG;
			case Opcodes.NEG_FLOAT -> FNEG;
			case Opcodes.NEG_DOUBLE -> DNEG;
			case Opcodes.INT_TO_LONG -> I2L;
			case Opcodes.INT_TO_FLOAT -> I2F;
			case Opcodes.INT_TO_DOUBLE -> I2D;
			case Opcodes.LONG_TO_INT -> L2I;
			case Opcodes.LONG_TO_FLOAT -> L2F;
			case Opcodes.LONG_TO_DOUBLE -> L2D;
			case Opcodes.FLOAT_TO_INT -> F2I;
			case Opcodes.FLOAT_TO_LONG -> F2L;
			case Opcodes.FLOAT_TO_DOUBLE -> F2D;
			case Opcodes.DOUBLE_TO_INT -> D2I;
			case Opcodes.DOUBLE_TO_LONG -> D2L;
			case Opcodes.DOUBLE_TO_FLOAT -> D2F;
			case Opcodes.INT_TO_BYTE -> I2B;
			case Opcodes.INT_TO_CHAR -> I2C;
			case Opcodes.INT_TO_SHORT -> I2S;
			default -> Integer.MIN_VALUE;
		});
		if (instruction.opcode() == Opcodes.NOT_INT) {
			ConversionSupport.pushInt(mv, -1);
			mv.visitInsn(IXOR);
		} else if (instruction.opcode() == Opcodes.NOT_LONG) {
			ConversionSupport.pushLong(mv, -1L);
			mv.visitInsn(LXOR);
		}
		finishValue(op, resultMode);
	}

	private void emitCompare(@NotNull IrOp op, @NotNull CompareInstruction instruction,
	                         @NotNull ResultMode resultMode) {
		loadInput(op, 0);
		loadInput(op, 1);
		mv.visitInsn(switch (instruction.opcode()) {
			case Opcodes.CMPL_FLOAT -> FCMPL;
			case Opcodes.CMPG_FLOAT -> FCMPG;
			case Opcodes.CMPL_DOUBLE -> DCMPL;
			case Opcodes.CMPG_DOUBLE -> DCMPG;
			case Opcodes.CMP_LONG -> LCMP;
			default -> throw new IllegalArgumentException("Unsupported compare opcode: " + instruction.opcode());
		});
		finishValue(op, resultMode);
	}

	private void emitArrayGet(@NotNull IrOp op, @NotNull ArrayInstruction instruction,
	                          @NotNull ResultMode resultMode) {
		ClassType arrayType = op.semantics().inputs().getFirst().expected().materializedType();
		ClassType elementType = arrayType instanceof ArrayType array
				? array.componentType() : op.type();
		loadInput(op, 0);
		loadInput(op, 1);
		mv.visitInsn(ConversionSupport.arrayLoadOpcode(elementType));
		finishValue(op, resultMode);
	}

	private void emitFilledNewArray(@NotNull IrOp op, @NotNull FilledNewArrayInstruction instruction,
	                               @NotNull ResultMode resultMode) {
		ConversionSupport.pushInt(mv, op.inputs().size());
		ConversionSupport.emitNewArray(mv, instruction.componentType());
		ClassType elementType = ConversionSupport.arrayElementType(
				ConversionSupport.normalizeArrayType(instruction.componentType()));
		for (int i = 0; i < op.inputs().size(); i++) {
			mv.visitInsn(DUP);
			ConversionSupport.pushInt(mv, i);
			loadInput(op, i);
			mv.visitInsn(ConversionSupport.arrayStoreOpcode(elementType));
		}
		finishValue(op, resultMode);
	}

	private void emitInvoke(@NotNull IrOp op, @NotNull InvokeInstruction instruction,
	                        @NotNull ResultMode resultMode) {
		if (isConstructorInvoke(instruction) && !op.inputs().isEmpty()) {
			IrValue receiver = op.inputs().getFirst().canonical();
			if (receiver instanceof IrOp receiverOp
					&& receiverOp.payload() instanceof NewInstanceInstruction newInstanceInstruction) {
				boolean keepConstructedInstance = host.shouldKeepConstructedInstance(receiverOp);
				boolean receiverAlreadyEmitted = host.isOperationEmitted(receiverOp);
				if (receiverAlreadyEmitted) {
					loadInput(op, 0);
				} else {
					mv.visitTypeInsn(NEW, newInstanceInstruction.type().internalName());
					if (keepConstructedInstance) {
						host.store(receiverOp);
						host.load(receiverOp, receiverOp.type());
					} else {
						mv.visitInsn(DUP);
					}
				}
				for (int i = 1; i < op.inputs().size(); i++)
					loadInput(op, i);
				mv.visitMethodInsn(INVOKESPECIAL, ConversionSupport.asmOwner(instruction.owner()),
						instruction.name(), instruction.type().descriptor(), false);
				if (!receiverAlreadyEmitted && !keepConstructedInstance) mv.visitInsn(POP);
				return;
			}
		}
		for (int i = 0; i < op.inputs().size(); i++) {
			if (host.tryEmitSyntheticLambda(op, instruction, i)) continue;
			loadInput(op, i);
		}
		mv.visitMethodInsn(ConversionSupport.invokeOpcode(instruction.opcode()),
				ConversionSupport.asmOwner(instruction.owner()), instruction.name(),
				instruction.type().descriptor(), instruction.opcode() == Invoke.INTERFACE);
		if (!ConversionSupport.isVoidType(instruction.type().returnType())) finishValue(op, resultMode);
	}

	private void emitInvokeCustom(@NotNull IrOp op, @NotNull InvokeCustomInstruction instruction,
	                              @NotNull ResultMode resultMode) {
		for (int i = 0; i < op.inputs().size(); i++)
			loadInput(op, i);
		mv.visitInvokeDynamicInsn(instruction.name(), instruction.type().descriptor(),
				ConversionSupport.asmHandle(instruction.handle()),
				ConversionSupport.bootstrapArguments(instruction.arguments()));
		if (!ConversionSupport.isVoidType(instruction.type().returnType())) finishValue(op, resultMode);
	}

	private void finishValue(@NotNull IrValue value, @NotNull ResultMode resultMode) {
		switch (resultMode) {
			case STORE -> host.store(value);
			case DISCARD -> IrValueEmitter.popValue(mv, value.type());
			case LEAVE_ON_STACK -> {
			}
		}
	}

	private static boolean isConstructorInvoke(@NotNull InvokeInstruction instruction) {
		return instruction.opcode() == Invoke.DIRECT && "<init>".equals(instruction.name());
	}

	private void loadInput(@NotNull IrOp op, int index) {
		if (index >= op.semantics().inputs().size())
			throw new IllegalStateException("Missing semantic input contract " + index + " for " + op);
		host.load(op.inputs().get(index), op.semantics().inputs().get(index).expected().materializedType());
	}

	/** Result handling for an operation's produced JVM value. */
	enum ResultMode {
		STORE,
		DISCARD,
		LEAVE_ON_STACK
	}

	interface Host {
		void load(@NotNull IrValue value, @NotNull ClassType expectedType);

		boolean tryEmitIncrement(@NotNull IrOp op, int constant, @NotNull ResultMode resultMode);

		/** Emits a proven in-place long accumulator update, if one is available. */
		boolean tryEmitLongIncrement(@NotNull IrOp op, @NotNull ResultMode resultMode);

		void store(@NotNull IrValue value);

		boolean shouldKeepConstructedInstance(@NotNull IrOp newInstanceOp);

		boolean isOperationEmitted(@NotNull IrOp op);

		/** Emits a proven zero-capture comparator lambda in place of its DEX synthetic class. */
		boolean tryEmitSyntheticLambda(@NotNull IrOp consumer, @NotNull InvokeInstruction instruction,
		                               int inputIndex);
	}
}
