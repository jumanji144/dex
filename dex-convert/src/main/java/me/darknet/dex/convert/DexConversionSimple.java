package me.darknet.dex.convert;

import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.ArrayLengthInstruction;
import me.darknet.dex.tree.definitions.instructions.Binary2AddrInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
import me.darknet.dex.tree.definitions.instructions.CompareInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstStringInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstTypeInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstWideInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.MonitorInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveObjectInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveResultInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveWideInstruction;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.NopInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.Result;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.SparseSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import me.darknet.dex.tree.definitions.instructions.UnaryInstruction;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.PrimitiveType;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;

import static me.darknet.dex.convert.ConversionSupport.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A Dex/Dalvik to Java class converter that directly emits Java bytecode using ASM.
 * <p>
 * This does not use SSA form or perform any optimizations. It is very straightforward
 * with only a few heuristics to defer materialization of constants and avoid unnecessary
 * loads/stores in simple cases.
 */
public class DexConversionSimple extends AbstractDexConversion {
	private static final InstanceType STRING_TYPE = Types.instanceType(String.class);
	private static final InstanceType CLASS_TYPE = Types.instanceType(Class.class);
	private static final InstanceType THROWABLE_TYPE = Types.instanceType(Throwable.class);

	@Override
	public @NotNull ConversionResult toClasses(@NotNull DexFile dex) {
		Map<String, byte[]> classes = new TreeMap<>();
		Map<String, Throwable> errors = new TreeMap<>();
		for (ClassDefinition cls : dex.definitions()) {
			String name = cls.getType().internalName();
			try {
				classes.put(name, toJavaClass(cls));
			} catch (Throwable t) {
				errors.put(name, t);
			}
		}
		return new ConversionResult(classes, errors);
	}

	@Override
	public byte @Nullable [] toJavaClass(@NotNull ClassDefinition cls) {
		ClassWriter cw = getWriterFactory().newWriter(cls);

		// Base class properties
		String name = cls.getType().internalName();
		String superName = cls.getSuperClass() == null ? null : cls.getSuperClass().internalName();
		String[] interfaces = cls.getInterfaces().stream().map(InstanceType::internalName).toArray(String[]::new);
		cw.visit(V1_8, cls.getAccess(), name, cls.getSignature(), superName, interfaces);

		// Source metadata
		cw.visitSource(cls.getSourceFile(), null);

		// Outer class metadata
		String outerClass = cls.getEnclosingClass() == null ? null : cls.getEnclosingClass().internalName();
		MemberIdentifier outerMethod = cls.getEnclosingMethod();
		String outerMethodName = outerMethod == null ? null : outerMethod.name();
		String outerMethodDesc = outerMethod == null ? null : outerMethod.descriptor();
		if (outerClass != null) cw.visitOuterClass(outerClass, outerMethodName, outerMethodDesc);

		// Inner classes
		for (InnerClass innerClass : cls.getInnerClasses()) {
			cw.visitInnerClass(innerClass.innerClassName(), innerClass.anonymous() ? null : innerClass.outerClassName(), innerClass.innerName(), innerClass.access());
		}

		// Annotations
		for (Annotation annotation : cls.getAnnotations()) {
			AnnotationPart part = annotation.annotation();
			AnnotationVisitor av = cw.visitAnnotation(part.type().descriptor(), annotation.visibility() > 0);
			ConversionSupport.visitAnnotation(av, annotation);
			av.visitEnd();
		}

		// Fields & Methods
		for (FieldMember field : cls.getFields().values()) {
			FieldVisitor fv = ((ClassVisitor) cw).visitField(field.getAccess(), field.getName(), field.getType().descriptor(),
					field.getSignature(), mapConstant(field.getStaticValue()));
			for (Annotation annotation : field.getAnnotations()) {
				AnnotationPart part = annotation.annotation();
				AnnotationVisitor av = fv.visitAnnotation(part.type().descriptor(), annotation.visibility() > 0);
				ConversionSupport.visitAnnotation(av, annotation);
				av.visitEnd();
			}
		}
		for (MethodMember method : cls.getMethods().values()) {
			String[] exceptions = method.getThrownTypes().isEmpty() ? null : method.getThrownTypes().toArray(new String[0]);
			MethodVisitor mv = ((ClassVisitor) cw).visitMethod(method.getAccess(), method.getName(), method.getType().descriptor(),
					method.getSignature(), exceptions);
			for (Annotation annotation : method.getAnnotations()) {
				AnnotationPart part = annotation.annotation();
				AnnotationVisitor av = mv.visitAnnotation(part.type().descriptor(), annotation.visibility() > 0);
				ConversionSupport.visitAnnotation(av, annotation);
				av.visitEnd();
			}
			visitCode(mv, method);
		}

		cw.visitEnd();

		return cw.toByteArray();
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param method
	 * 		Method to write.
	 */
	private static void visitCode(@NotNull MethodVisitor mv, @NotNull MethodMember method) {
		Code code = method.getCode();
		if (code == null) return;

		mv.visitCode();

		// First pass to assign labels to all label instructions and try-catch boundaries
		Map<Integer, org.objectweb.asm.Label> labelMapping = new HashMap<>();
		org.objectweb.asm.Label terminalLabel = new org.objectweb.asm.Label();
		BiFunction<Label, String, org.objectweb.asm.Label> lbl = (label, context) -> {
			org.objectweb.asm.Label direct = labelMapping.get(label.position());
			if (direct != null) return direct;
			if (context.startsWith("try-end")) {
				Integer next = labelMapping.keySet().stream()
						.filter(position -> position > label.position())
						.min(Integer::compareTo)
						.orElse(null);
				return next == null ? terminalLabel : labelMapping.get(next);
			}
			throw new NullPointerException("Missing " + context + " label");
		};
		for (Instruction instruction : code.getInstructions()) {
			if (instruction instanceof Label label) {
				labelMapping.computeIfAbsent(label.position(), ignored -> new org.objectweb.asm.Label());
			}
		}

		// Second pass to emit instructions, materializing constants as late as possible and trying to avoid unnecessary loads/stores
		Set<Integer> controlFlowTargets = collectControlFlowTargets(code);
		RegisterLayout layout = new RegisterLayout(method, code);
		RegisterState state = new RegisterState(code.getRegisters(), code.tryCatch().isEmpty());
		initializeParameters(mv, method, code, layout, state);

		// Iterate through instructions, flushing any pending result before any instruction that can't consume it,
		// and before any control flow instruction since we don't want to accidentally let it live across basic blocks
		// and be consumed by the wrong instruction or not at all.
		PendingResult pending = null;
		var instructions = code.getInstructions();
		for (int index = 0; index < instructions.size(); index++) {
			Instruction instruction = instructions.get(index);
			if (instruction instanceof Label label) {
				if (controlFlowTargets.contains(label.position())) {
					materializeLiveConstantsAtLabel(mv, layout, state, instructions, index);
					state.clearKnownConstants();
				}
				pending = flushPendingResult(mv, pending);
				mv.visitLabel(lbl.apply(label, "code"));
				continue;
			}

			// If this instruction is not a move-result, then any pending result must be flushed before it
			// since it can only be consumed by a move-result and we don't want to accidentally consume it
			// with the wrong instruction or let it live longer than necessary.
			if (!(instruction instanceof MoveResultInstruction)) {
				pending = flushPendingResult(mv, pending);
			}

			switch (instruction) {
				case ArrayInstruction arrayInstruction -> visitArrayInstruction(mv, arrayInstruction, layout, state);
				case ArrayLengthInstruction arrayLengthInstruction ->
						visitArrayLengthInstruction(mv, arrayLengthInstruction, layout, state);
				case Binary2AddrInstruction binary2AddrInstruction ->
						visitBinary2AddrInstruction(mv, binary2AddrInstruction, layout, state);
				case BinaryInstruction binaryInstruction ->
						visitBinaryInstruction(mv, binaryInstruction, layout, state);
				case BinaryLiteralInstruction binaryLiteralInstruction ->
						visitBinaryLiteralInstruction(mv, binaryLiteralInstruction, layout, state);
				case BranchInstruction branchInstruction ->
						visitBranchInstruction(mv, branchInstruction, layout, state, lbl);
				case BranchZeroInstruction branchZeroInstruction ->
						visitBranchZeroInstruction(mv, branchZeroInstruction, layout, state, lbl);
				case CheckCastInstruction checkCastInstruction ->
						visitCheckCastInstruction(mv, checkCastInstruction, layout, state);
				case CompareInstruction compareInstruction ->
						visitCompareInstruction(mv, compareInstruction, layout, state);
				case ConstInstruction constInstruction -> visitConstInstruction(mv, constInstruction, layout, state,
						isDeadStoreInLinearTail(instructions, index, constInstruction.register()));
				case ConstStringInstruction constStringInstruction ->
						visitConstStringInstruction(mv, constStringInstruction, layout, state);
				case ConstTypeInstruction constTypeInstruction ->
						visitConstTypeInstruction(mv, constTypeInstruction, layout, state);
				case ConstWideInstruction constWideInstruction ->
						visitConstWideInstruction(mv, constWideInstruction, layout, state);
				case FillArrayDataInstruction fillArrayDataInstruction ->
						visitFillArrayDataInstruction(mv, fillArrayDataInstruction, layout, state);
				case FilledNewArrayInstruction filledNewArrayInstruction ->
						pending = visitFilledNewArrayInstruction(mv, filledNewArrayInstruction, layout, state);
				case GotoInstruction gotoInstruction -> {
					state.materializeConstants(mv, layout, false, true);
					mv.visitJumpInsn(GOTO, lbl.apply(gotoInstruction.jump(), "goto"));
				}
				case InstanceFieldInstruction instanceFieldInstruction ->
						visitInstanceFieldInstruction(mv, instanceFieldInstruction, layout, state);
				case InstanceOfInstruction instanceOfInstruction ->
						visitInstanceOfInstruction(mv, instanceOfInstruction, layout, state);
				case InvokeCustomInstruction invokeCustomInstruction ->
						pending = visitInvokeCustomInstruction(mv, invokeCustomInstruction, layout, state);
				case InvokeInstruction invokeInstruction ->
						pending = visitInvokeInstruction(mv, invokeInstruction, layout, state);
				case MonitorInstruction monitorInstruction ->
						visitMonitorInstruction(mv, monitorInstruction, layout, state);
				case MoveExceptionInstruction moveExceptionInstruction ->
						storeReferenceRegister(mv, layout, state, moveExceptionInstruction.register(), THROWABLE_TYPE);
				case MoveInstruction moveInstruction -> visitMoveInstruction(mv, moveInstruction, layout, state);
				case MoveObjectInstruction moveObjectInstruction ->
						visitMoveObjectInstruction(mv, moveObjectInstruction, layout, state);
				case MoveResultInstruction moveResultInstruction ->
						pending = consumePendingResult(mv, moveResultInstruction, pending, layout, state);
				case MoveWideInstruction moveWideInstruction ->
						visitMoveWideInstruction(mv, moveWideInstruction, layout, state);
				case NewArrayInstruction newArrayInstruction ->
						visitNewArrayInstruction(mv, newArrayInstruction, layout, state);
				case NewInstanceInstruction newInstanceInstruction -> {
					mv.visitTypeInsn(NEW, newInstanceInstruction.type().internalName());
					storeReferenceRegister(mv, layout, state, newInstanceInstruction.dest(), newInstanceInstruction.type());
				}
				case Label ignored -> {}
				case NopInstruction ignored -> mv.visitInsn(NOP);
				case PackedSwitchInstruction packedSwitchInstruction ->
						visitPackedSwitchInstruction(mv, packedSwitchInstruction, layout, state, lbl);
				case ReturnInstruction returnInstruction ->
						visitReturnInstruction(mv, method.getType(), returnInstruction, layout, state);
				case SparseSwitchInstruction sparseSwitchInstruction ->
						visitSparseSwitchInstruction(mv, sparseSwitchInstruction, layout, state, lbl);
				case StaticFieldInstruction staticFieldInstruction ->
						visitStaticFieldInstruction(mv, staticFieldInstruction, layout, state);
				case ThrowInstruction throwInstruction -> visitThrowInstruction(mv, throwInstruction, layout, state);
				case UnaryInstruction unaryInstruction -> visitUnaryInstruction(mv, unaryInstruction, layout, state);
			}
		}

		// After all instructions have been emitted, there should be no pending result left.
		// If there is, it means it was not consumed by a move-result instruction as expected, which indicates a bug in the converter.
		pending = flushPendingResult(mv, pending);
		if (pending != null)
			throw new IllegalStateException("Unconsumed pending result in " + method.getName() + method.getType());
		mv.visitLabel(terminalLabel);

		// Finally, emit try-catch blocks.
		// We have to do this after emitting all instructions since they can refer to labels that come after them,
		// and ASM requires try-catch blocks to be visited after the labels they refer to.
		for (TryCatch tryCatch : TryCatchSupport.effectiveTryCatches(code)) {
			var startLabel = lbl.apply(tryCatch.begin(), "try-start");
			var endLabel = lbl.apply(tryCatch.end(), "try-end");
			for (Handler handler : tryCatch.handlers()) {
				var handlerLabel = lbl.apply(handler.handler(), "try-handler");
				String catchType = handler.isCatchAll() ? null : handler.exceptionType().internalName();
				mv.visitTryCatchBlock(startLabel, endLabel, handlerLabel, catchType);
			}
		}

		mv.visitMaxs(0xFF, 0xFF);
		mv.visitEnd();
	}

	/**
	 * @param code
	 * 		Method code to analyze.
	 *
	 * @return Set of instruction offsets that are targets of control flow instructions (branches, switches, gotos).
	 */
	private static @NotNull Set<Integer> collectControlFlowTargets(@NotNull Code code) {
		Set<Integer> targets = new HashSet<>();
		for (Instruction instruction : code.getInstructions()) {
			switch (instruction) {
				case BranchInstruction branchInstruction -> targets.add(branchInstruction.label().position());
				case BranchZeroInstruction branchZeroInstruction ->
						targets.add(branchZeroInstruction.label().position());
				case GotoInstruction gotoInstruction -> targets.add(gotoInstruction.jump().position());
				case PackedSwitchInstruction packedSwitchInstruction ->
						packedSwitchInstruction.targets().forEach(target -> targets.add(target.position()));
				case SparseSwitchInstruction sparseSwitchInstruction ->
						sparseSwitchInstruction.targets().values().forEach(target -> targets.add(target.position()));
				default -> {}
			}
		}
		return targets;
	}

	/**
	 * Used to clean up trailing dead stores that aren't read later and aren't needed for control flow.
	 * This isn't a proper dead store elimination pass, but catches some common cases seen in sample applications.
	 *
	 * @param instructions
	 * 		Instructions to analyze.
	 * @param currentIndex
	 * 		Current instruction index to check from.
	 * @param register
	 * 		Register to check for dead store.
	 *
	 * @return {@code true} when the instruction at the current index is the only access to/from the register.
	 */
	private static boolean isDeadStoreInLinearTail(@NotNull List<Instruction> instructions,
	                                               int currentIndex, int register) {
		for (int i = currentIndex + 1; i < instructions.size(); i++) {
			Instruction instruction = instructions.get(i);

			// If we reach another label or any instruction that reads the register, then this store is not dead since
			// it may be needed for control flow or by later instructions.
			// Without a more proper pass structure with liveness analysis we cannot tell for sure.
			if (instruction instanceof Label) return false;

			// If we read/write to it again, then its not dead.
			if (instructionReadsRegister(instruction, register)) return false;
			if (instructionWritesRegister(instruction, register)) return true;

			// If we reach any other control flow instruction, then this store is not dead since it may be needed for control flow.
			if (instruction instanceof BranchInstruction
					|| instruction instanceof BranchZeroInstruction
					|| instruction instanceof GotoInstruction
					|| instruction instanceof PackedSwitchInstruction
					|| instruction instanceof SparseSwitchInstruction) {
				return false;
			}

			// If we reach terminal control flow instructions, we're done iterating.
			if (instruction instanceof ReturnInstruction || instruction instanceof ThrowInstruction) break;
		}

		// End of code reached without any other access to the register, so this store is dead.
		return true;
	}

	/**
	 * @param instructions
	 * 		List of all instructions in the method.
	 * @param currentIndex
	 * 		Current instruction index to check from.
	 * @param register
	 * 		Register to check for future reads.
	 *
	 * @return {@code true} if the register is read later in the current basic block, {@code false} otherwise.
	 */
	private static boolean isRegisterReadInCurrentBasicBlock(@NotNull List<Instruction> instructions,
	                                                         int currentIndex, int register) {
		// Basically the same idea as the method above, same comments apply.
		// The main difference is checking for a read from the register.
		for (int i = currentIndex + 1; i < instructions.size(); i++) {
			Instruction instruction = instructions.get(i);
			if (instruction instanceof Label) return false;
			if (instructionReadsRegister(instruction, register)) return true;
			if (instructionWritesRegister(instruction, register)) return false;
			if (instruction instanceof BranchInstruction
					|| instruction instanceof BranchZeroInstruction
					|| instruction instanceof GotoInstruction
					|| instruction instanceof PackedSwitchInstruction
					|| instruction instanceof SparseSwitchInstruction
					|| instruction instanceof ReturnInstruction
					|| instruction instanceof ThrowInstruction) {
				return false;
			}
		}
		return false;
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param instructions
	 * 		List of all instructions in the method.
	 * @param labelIndex
	 * 		Current instruction index of the label to materialize constants for.
	 */
	private static void materializeLiveConstantsAtLabel(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                                    @NotNull RegisterState state,
	                                                    @NotNull List<Instruction> instructions,
	                                                    int labelIndex) {
		// Only defer constants when we have at least one, and when we're actually able to defer them.
		// If we have none, then there's no point in trying, and if we can't defer them, then we shouldn't try
		// since it would just add unnecessary complexity and overhead.
		if (!state.canDeferConstants()) return;

		// For all registers, if they are read later in the current basic block, and we have a known constant for them,
		// then defer materializing that constant until this label.
		for (int register = 0; register < state.registerCount(); register++) {
			if (!isRegisterReadInCurrentBasicBlock(instructions, labelIndex, register)) continue;

			Integer wordConstant = state.wordConstant(register);
			if (wordConstant != null) {
				pushInt(mv, wordConstant);
				mv.visitVarInsn(ISTORE, layout.word(register));
			}

			DeferredReferenceValue referenceConstant = state.referenceConstant(register);
			if (referenceConstant != null) {
				referenceConstant.emit(mv);
				mv.visitVarInsn(ASTORE, layout.ref(register));
			}
		}
	}

	/**
	 * @param instruction
	 * 		Instruction to check.
	 * @param register
	 * 		Register to check for being read.
	 *
	 * @return {@code true} if the instruction reads from the register, {@code false} otherwise.
	 */
	private static boolean instructionReadsRegister(@NotNull Instruction instruction, int register) {
		return switch (instruction) {
			case ArrayInstruction arrayInstruction -> arrayInstruction.array() == register
					|| arrayInstruction.index() == register
					|| (arrayInstruction.opcode() >= Opcodes.APUT && arrayInstruction.value() == register);
			case ArrayLengthInstruction arrayLengthInstruction -> arrayLengthInstruction.array() == register;
			case Binary2AddrInstruction binary2AddrInstruction ->
					binary2AddrInstruction.a() == register || binary2AddrInstruction.b() == register;
			case BinaryInstruction binaryInstruction ->
					binaryInstruction.a() == register || binaryInstruction.b() == register;
			case BinaryLiteralInstruction binaryLiteralInstruction -> binaryLiteralInstruction.src() == register;
			case BranchInstruction branchInstruction ->
					branchInstruction.a() == register || branchInstruction.b() == register;
			case BranchZeroInstruction branchZeroInstruction -> branchZeroInstruction.a() == register;
			case CheckCastInstruction checkCastInstruction -> checkCastInstruction.register() == register;
			case CompareInstruction compareInstruction ->
					compareInstruction.a() == register || compareInstruction.b() == register;
			case FillArrayDataInstruction fillArrayDataInstruction -> fillArrayDataInstruction.array() == register;
			case FilledNewArrayInstruction filledNewArrayInstruction ->
					filledNewArrayUsesRegister(filledNewArrayInstruction, register);
			case InstanceFieldInstruction instanceFieldInstruction -> instanceFieldInstruction.instance() == register
					|| (instanceFieldInstruction.opcode() >= Opcodes.IPUT && instanceFieldInstruction.value() == register);
			case InstanceOfInstruction instanceOfInstruction -> instanceOfInstruction.register() == register;
			case InvokeCustomInstruction invokeCustomInstruction ->
					invokeCustomUsesRegister(invokeCustomInstruction, register);
			case InvokeInstruction invokeInstruction -> invokeUsesRegister(invokeInstruction, register);
			case MonitorInstruction monitorInstruction -> monitorInstruction.register() == register;
			case MoveInstruction moveInstruction -> moveInstruction.from() == register;
			case MoveObjectInstruction moveObjectInstruction -> moveObjectInstruction.from() == register;
			case MoveWideInstruction moveWideInstruction -> moveWideInstruction.from() == register;
			case NewArrayInstruction newArrayInstruction -> newArrayInstruction.sizeRegister() == register;
			case PackedSwitchInstruction packedSwitchInstruction -> packedSwitchInstruction.register() == register;
			case ReturnInstruction returnInstruction ->
					returnInstruction.opcode() != Opcodes.RETURN_VOID && returnInstruction.register() == register;
			case SparseSwitchInstruction sparseSwitchInstruction -> sparseSwitchInstruction.register() == register;
			case StaticFieldInstruction staticFieldInstruction ->
					staticFieldInstruction.opcode() >= Opcodes.SPUT && staticFieldInstruction.value() == register;
			case ThrowInstruction throwInstruction -> throwInstruction.value() == register;
			case UnaryInstruction unaryInstruction -> unaryInstruction.source() == register;
			default -> false;
		};
	}

	/**
	 * @param instruction
	 * 		Instruction to check.
	 * @param register
	 * 		Register to check for being written to.
	 *
	 * @return {@code true} if the instruction writes to the register, {@code false} otherwise.
	 */
	private static boolean instructionWritesRegister(@NotNull Instruction instruction, int register) {
		return switch (instruction) {
			case ArrayInstruction arrayInstruction ->
					arrayInstruction.opcode() < Opcodes.APUT && arrayInstruction.value() == register;
			case ArrayLengthInstruction arrayLengthInstruction -> arrayLengthInstruction.dest() == register;
			case Binary2AddrInstruction binary2AddrInstruction -> binary2AddrInstruction.a() == register;
			case BinaryInstruction binaryInstruction -> binaryInstruction.dest() == register;
			case BinaryLiteralInstruction binaryLiteralInstruction -> binaryLiteralInstruction.dest() == register;
			case CheckCastInstruction checkCastInstruction -> checkCastInstruction.register() == register;
			case CompareInstruction compareInstruction -> compareInstruction.dest() == register;
			case ConstInstruction constInstruction -> constInstruction.register() == register;
			case ConstStringInstruction constStringInstruction -> constStringInstruction.register() == register;
			case ConstTypeInstruction constTypeInstruction -> constTypeInstruction.register() == register;
			case ConstWideInstruction constWideInstruction -> constWideInstruction.register() == register;
			case InstanceFieldInstruction instanceFieldInstruction ->
					instanceFieldInstruction.opcode() < Opcodes.IPUT && instanceFieldInstruction.value() == register;
			case InstanceOfInstruction instanceOfInstruction -> instanceOfInstruction.destination() == register;
			case MoveExceptionInstruction moveExceptionInstruction -> moveExceptionInstruction.register() == register;
			case MoveInstruction moveInstruction -> moveInstruction.to() == register;
			case MoveObjectInstruction moveObjectInstruction -> moveObjectInstruction.to() == register;
			case MoveResultInstruction moveResultInstruction -> moveResultInstruction.to() == register;
			case MoveWideInstruction moveWideInstruction -> moveWideInstruction.to() == register;
			case NewArrayInstruction newArrayInstruction -> newArrayInstruction.dest() == register;
			case NewInstanceInstruction newInstanceInstruction -> newInstanceInstruction.dest() == register;
			case StaticFieldInstruction staticFieldInstruction ->
					staticFieldInstruction.opcode() < Opcodes.SPUT && staticFieldInstruction.value() == register;
			case UnaryInstruction unaryInstruction -> unaryInstruction.dest() == register;
			default -> false;
		};
	}

	/**
	 * @param instruction
	 * 		Instruction to check.
	 * @param register
	 * 		Register to check for being used as an argument.
	 *
	 * @return {@code true} if the instruction uses the register as an argument, {@code false} otherwise.
	 */
	private static boolean filledNewArrayUsesRegister(@NotNull FilledNewArrayInstruction instruction, int register) {
		if (instruction.isRange()) {
			return register >= instruction.first() && register <= instruction.last();
		}
		for (int candidate : instruction.registers()) {
			if (candidate == register) return true;
		}
		return false;
	}

	/**
	 * @param instruction
	 * 		Instruction to check.
	 * @param register
	 * 		Register to check for being used as an argument.
	 *
	 * @return {@code true} if the instruction uses the register as an argument, {@code false} otherwise.
	 */
	private static boolean invokeCustomUsesRegister(@NotNull InvokeCustomInstruction instruction, int register) {
		if (instruction.isRange()) {
			int count = 0;
			for (ClassType parameterType : instruction.type().parameterTypes()) {
				count += slotSize(parameterType);
			}
			return register >= instruction.first() && register < instruction.first() + count;
		}
		for (int candidate : instruction.argumentRegisters()) {
			if (candidate == register) return true;
		}
		return false;
	}

	/**
	 * @param instruction
	 * 		Instruction to check.
	 * @param register
	 * 		Register to check for being used as an argument.
	 *
	 * @return {@code true} if the instruction uses the register as an argument, {@code false} otherwise.
	 */
	private static boolean invokeUsesRegister(@NotNull InvokeInstruction instruction, int register) {
		// For range invokes, the argument registers are contiguous and start from the "first" register,
		// and the count depends on the method being invoked.
		if (instruction.isRange()) {
			int count = instruction.opcode() == Invoke.STATIC ? 0 : 1;
			for (ClassType parameterType : instruction.type().parameterTypes())
				count += slotSize(parameterType);
			return register >= instruction.first() && register < instruction.first() + count;
		}

		// For non-range invokes, the register can be used as an argument even if it's not explicitly listed as one,
		// since for instance method calls the "this" register is implicit and not included in the argument list.
		for (int candidate : instruction.arguments())
			if (candidate == register)
				return true;
		return false;
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Array instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitArrayInstruction(@NotNull MethodVisitor mv, @NotNull ArrayInstruction instruction,
	                                          @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		ClassType elementType = arrayElementType(instruction, state);
		boolean isGet = instruction.opcode() < Opcodes.APUT;

		// Load the array reference and index, then either load or store the array element depending on whether this is an AGET or APUT instruction.
		loadReferenceRegister(mv, layout, state, instruction.array());
		loadWordRegister(mv, layout, state, instruction.index());
		if (isGet) {
			mv.visitInsn(arrayLoadOpcode(elementType));
			storeRegisterForType(mv, layout, state, instruction.value(), elementType);
		} else {
			loadRegisterForType(mv, layout, state, instruction.value(), elementType);
			mv.visitInsn(arrayStoreOpcode(elementType));
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Array length instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitArrayLengthInstruction(@NotNull MethodVisitor mv, @NotNull ArrayLengthInstruction instruction,
	                                                @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		loadReferenceRegister(mv, layout, state, instruction.array());
		mv.visitInsn(ARRAYLENGTH);
		storeWordRegister(mv, layout, state, instruction.dest());
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Binary instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitBinaryInstruction(@NotNull MethodVisitor mv, @NotNull BinaryInstruction instruction,
	                                           @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		int opcode = instruction.opcode();

		// For int binary operations, load the two operand registers as ints, emit the appropriate int binary opcode,
		// and store the result back to the destination register.
		if (isIntBinary(opcode)) {
			loadWordRegister(mv, layout, state, instruction.a());
			loadWordRegister(mv, layout, state, instruction.b());
			mv.visitInsn(intBinaryOpcode(opcode));
			storeWordRegister(mv, layout, state, instruction.dest());
			return;
		}

		// For float binary operations, same idea.
		if (isFloatBinary(opcode)) {
			loadWordAsFloat(mv, layout, state, instruction.a());
			loadWordAsFloat(mv, layout, state, instruction.b());
			mv.visitInsn(floatBinaryOpcode(opcode));
			storeFloatRegister(mv, layout, state, instruction.dest());
			return;
		}

		// For long binary operations, same idea, with an edge-case for long shifts where the second operand is an int, not a long.
		// Also, the values are wide and take up two registers vs single register words.
		if (isLongBinary(opcode)) {
			loadWideRegister(mv, layout, instruction.a());
			if (isLongShift(opcode)) {
				loadWordRegister(mv, layout, state, instruction.b());
			} else {
				loadWideRegister(mv, layout, instruction.b());
			}
			mv.visitInsn(longBinaryOpcode(opcode));
			storeWideRegister(mv, layout, state, instruction.dest());
			return;
		}

		// For double binary operations, same idea as long, but no need for an edge case since there are no double shifts.
		loadWideAsDouble(mv, layout, instruction.a());
		loadWideAsDouble(mv, layout, instruction.b());
		mv.visitInsn(doubleBinaryOpcode(opcode));
		storeDoubleRegister(mv, layout, state, instruction.dest());
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Binary2Addr instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitBinary2AddrInstruction(@NotNull MethodVisitor mv, @NotNull Binary2AddrInstruction instruction,
	                                                @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		// Same idea as the 'visitBinaryInstruction' method above,
		// but the first operand register is also the destination register.
		int opcode = instruction.opcode();
		if (isIntBinary2Addr(opcode)) {
			loadWordRegister(mv, layout, state, instruction.a());
			loadWordRegister(mv, layout, state, instruction.b());
			mv.visitInsn(intBinaryOpcode(opcode - (Opcodes.ADD_INT_2ADDR - Opcodes.ADD_INT)));
			storeWordRegister(mv, layout, state, instruction.a());
			return;
		}

		if (isFloatBinary2Addr(opcode)) {
			loadWordAsFloat(mv, layout, state, instruction.a());
			loadWordAsFloat(mv, layout, state, instruction.b());
			mv.visitInsn(floatBinaryOpcode(opcode - (Opcodes.ADD_FLOAT_2ADDR - Opcodes.ADD_FLOAT)));
			storeFloatRegister(mv, layout, state, instruction.a());
			return;
		}

		if (isLongBinary2Addr(opcode)) {
			loadWideRegister(mv, layout, instruction.a());
			if (isLongShift2Addr(opcode)) {
				loadWordRegister(mv, layout, state, instruction.b());
			} else {
				loadWideRegister(mv, layout, instruction.b());
			}
			mv.visitInsn(longBinaryOpcode(opcode - (Opcodes.ADD_LONG_2ADDR - Opcodes.ADD_LONG)));
			storeWideRegister(mv, layout, state, instruction.a());
			return;
		}

		loadWideAsDouble(mv, layout, instruction.a());
		loadWideAsDouble(mv, layout, instruction.b());
		mv.visitInsn(doubleBinaryOpcode(opcode - (Opcodes.ADD_DOUBLE_2ADDR - Opcodes.ADD_DOUBLE)));
		storeDoubleRegister(mv, layout, state, instruction.a());
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Binary literal instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitBinaryLiteralInstruction(@NotNull MethodVisitor mv, @NotNull BinaryLiteralInstruction instruction,
	                                                  @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		loadWordRegister(mv, layout, state, instruction.src());
		pushInt(mv, instruction.constant());

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
			default ->
					throw new IllegalArgumentException("Unsupported binary literal opcode: 0x" + Integer.toHexString(instruction.opcode()));
		}

		storeWordRegister(mv, layout, state, instruction.dest());
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Branch instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param lbl
	 * 		Label mapping function to get ASM labels for Dex labels.
	 */
	private static void visitBranchInstruction(@NotNull MethodVisitor mv, @NotNull BranchInstruction instruction,
	                                           @NotNull RegisterLayout layout, @NotNull RegisterState state,
	                                           @NotNull BiFunction<Label, String, org.objectweb.asm.Label> lbl) {
		// Materialize any pending constants before a branch instruction since we don't want them
		// to be accidentally live across basic blocks and consumed by the wrong instruction or not at all.
		state.materializeConstants(mv, layout, false, true);

		// Check if this is a boolean branch instruction that we can optimize with an ifeq/ifne, and if so, emit that directly.
		org.objectweb.asm.Label destination = lbl.apply(instruction.label(), "branch");
		if (tryVisitBooleanBranchInstruction(mv, instruction, layout, state, destination))
			return;

		// For reference comparisons, we can use the more efficient reference compare opcodes if both sides are references.
		boolean referenceCompare = instruction.opcode() == Opcodes.IF_EQ || instruction.opcode() == Opcodes.IF_NE;
		if (referenceCompare && usesReferenceCompare(state, instruction.a(), instruction.b())) {
			loadReferenceRegister(mv, layout, state, instruction.a());
			loadReferenceRegister(mv, layout, state, instruction.b());
			mv.visitJumpInsn(instruction.opcode() == Opcodes.IF_EQ ? IF_ACMPEQ : IF_ACMPNE, destination);
			return;
		}

		// Otherwise, this is a normal integer comparison branch.
		// Load the two operand registers as ints, and emit the appropriate int compare branch opcode.
		loadWordRegister(mv, layout, state, instruction.a());
		loadWordRegister(mv, layout, state, instruction.b());
		switch (instruction.opcode()) {
			case Opcodes.IF_EQ -> mv.visitJumpInsn(IF_ICMPEQ, destination);
			case Opcodes.IF_NE -> mv.visitJumpInsn(IF_ICMPNE, destination);
			case Opcodes.IF_LT -> mv.visitJumpInsn(IF_ICMPLT, destination);
			case Opcodes.IF_GE -> mv.visitJumpInsn(IF_ICMPGE, destination);
			case Opcodes.IF_GT -> mv.visitJumpInsn(IF_ICMPGT, destination);
			case Opcodes.IF_LE -> mv.visitJumpInsn(IF_ICMPLE, destination);
			default ->
					throw new IllegalArgumentException("Unsupported branch opcode: 0x" + Integer.toHexString(instruction.opcode()));
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Branch instruction to check for being a boolean branch.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param destination
	 * 		ASM label to jump to if the branch is taken.
	 *
	 * @return {@code true} if the instruction was recognized as a boolean branch and emitted, {@code false} otherwise.
	 */
	private static boolean tryVisitBooleanBranchInstruction(@NotNull MethodVisitor mv,
	                                                        @NotNull BranchInstruction instruction,
	                                                        @NotNull RegisterLayout layout,
	                                                        @NotNull RegisterState state,
	                                                        @NotNull org.objectweb.asm.Label destination) {
		// Only IF_EQ and IF_NE can be optimized as boolean branches.
		if (instruction.opcode() != Opcodes.IF_EQ && instruction.opcode() != Opcodes.IF_NE)
			return false;

		// Check if one side is a boolean constant and the other side is a boolean word register.
		// If so we can emit a more efficient IFEQ or IFNE instruction that directly checks the register against zero,
		// without needing to load the constant into another register and do a normal integer comparison.
		Integer rightConstant = wordConstantOrZero(state, instruction.b());
		if (state.isBooleanWord(instruction.a()) && rightConstant != null && (rightConstant == 0 || rightConstant == 1)) {
			loadWordRegister(mv, layout, state, instruction.a());
			mv.visitJumpInsn(booleanBranchOpcode(instruction.opcode(), rightConstant), destination);
			return true;
		}

		// Same idea, but with the other side as the constant.
		Integer leftConstant = wordConstantOrZero(state, instruction.a());
		if (state.isBooleanWord(instruction.b()) && leftConstant != null && (leftConstant == 0 || leftConstant == 1)) {
			loadWordRegister(mv, layout, state, instruction.b());
			mv.visitJumpInsn(booleanBranchOpcode(instruction.opcode(), leftConstant), destination);
			return true;
		}

		return false;
	}

	/**
	 * @param compareOpcode
	 * 		Branch opcode.
	 * @param constant
	 * 		Constant value that the boolean register is being compared against.
	 *
	 * @return Opcode for the branch instruction based on the constant value.
	 */
	private static int booleanBranchOpcode(int compareOpcode, int constant) {
		return switch (compareOpcode) {
			case Opcodes.IF_EQ -> constant == 0 ? IFEQ : IFNE;
			case Opcodes.IF_NE -> constant == 0 ? IFNE : IFEQ;
			default ->
					throw new IllegalArgumentException("Unsupported boolean branch opcode: 0x" + Integer.toHexString(compareOpcode));
		};
	}

	/**
	 * @param state
	 * 		Current register state.
	 * @param register
	 * 		Register to check.
	 *
	 * @return Constant value for the register, treating ZERO registers as the constant value 0,
	 * or {@code null} if the register is not a known constant.
	 */
	private static @Nullable Integer wordConstantOrZero(@NotNull RegisterState state, int register) {
		return state.kind(register) == RegisterKind.ZERO ?
				Integer.valueOf(0) :
				state.wordConstant(register);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		BranchZero instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param lbl
	 * 		Label mapping function to get ASM labels for Dex labels.
	 */
	private static void visitBranchZeroInstruction(@NotNull MethodVisitor mv, @NotNull BranchZeroInstruction instruction,
	                                               @NotNull RegisterLayout layout, @NotNull RegisterState state,
	                                               @NotNull BiFunction<Label, String, org.objectweb.asm.Label> lbl) {
		// Materialize any pending constants before a branch instruction since we don't want them
		// to be accidentally live across basic blocks and consumed by the wrong instruction or not at all.
		state.materializeConstants(mv, layout, false, true);

		// For boolean branches comparing against zero, we can optimize with ifeq/ifne directly,
		// without needing to load the constant into another register and do a normal integer comparison.
		org.objectweb.asm.Label destination = lbl.apply(instruction.label(), "branch");
		if ((instruction.opcode() == Opcodes.IF_EQZ || instruction.opcode() == Opcodes.IF_NEZ)
				&& state.kind(instruction.a()) == RegisterKind.REFERENCE) {
			loadReferenceRegister(mv, layout, state, instruction.a());
			mv.visitJumpInsn(instruction.opcode() == Opcodes.IF_EQZ ? IFNULL : IFNONNULL, destination);
			return;
		}

		// Otherwise, this is a normal integer comparison against zero.
		// Load the operand register as an int, and emit the appropriate int compare against zero branch opcode.
		loadWordRegister(mv, layout, state, instruction.a());
		switch (instruction.opcode()) {
			case Opcodes.IF_EQZ -> mv.visitJumpInsn(IFEQ, destination);
			case Opcodes.IF_NEZ -> mv.visitJumpInsn(IFNE, destination);
			case Opcodes.IF_LTZ -> mv.visitJumpInsn(IFLT, destination);
			case Opcodes.IF_GEZ -> mv.visitJumpInsn(IFGE, destination);
			case Opcodes.IF_GTZ -> mv.visitJumpInsn(IFGT, destination);
			case Opcodes.IF_LEZ -> mv.visitJumpInsn(IFLE, destination);
			default ->
					throw new IllegalArgumentException("Unsupported zero-branch opcode: 0x" + Integer.toHexString(instruction.opcode()));
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		CheckCast instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitCheckCastInstruction(@NotNull MethodVisitor mv, @NotNull CheckCastInstruction instruction,
	                                              @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		loadReferenceRegister(mv, layout, state, instruction.register());
		mv.visitTypeInsn(CHECKCAST, asmTypeOperand(instruction.type()));
		storeReferenceRegister(mv, layout, state, instruction.register(), asReferenceType(instruction.type()));
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Compare instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitCompareInstruction(@NotNull MethodVisitor mv, @NotNull CompareInstruction instruction,
	                                            @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		switch (instruction.opcode()) {
			case Opcodes.CMPL_FLOAT -> {
				loadWordAsFloat(mv, layout, state, instruction.a());
				loadWordAsFloat(mv, layout, state, instruction.b());
				mv.visitInsn(FCMPL);
			}
			case Opcodes.CMPG_FLOAT -> {
				loadWordAsFloat(mv, layout, state, instruction.a());
				loadWordAsFloat(mv, layout, state, instruction.b());
				mv.visitInsn(FCMPG);
			}
			case Opcodes.CMPL_DOUBLE -> {
				loadWideAsDouble(mv, layout, instruction.a());
				loadWideAsDouble(mv, layout, instruction.b());
				mv.visitInsn(DCMPL);
			}
			case Opcodes.CMPG_DOUBLE -> {
				loadWideAsDouble(mv, layout, instruction.a());
				loadWideAsDouble(mv, layout, instruction.b());
				mv.visitInsn(DCMPG);
			}
			case Opcodes.CMP_LONG -> {
				loadWideRegister(mv, layout, instruction.a());
				loadWideRegister(mv, layout, instruction.b());
				mv.visitInsn(LCMP);
			}
			default ->
					throw new IllegalArgumentException("Unsupported compare opcode: 0x" + Integer.toHexString(instruction.opcode()));
		}
		storeWordRegister(mv, layout, state, instruction.dest());
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Const instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param deadStore
	 * 		Whether this instruction is a dead store.
	 */
	private static void visitConstInstruction(@NotNull MethodVisitor mv, @NotNull ConstInstruction instruction,
	                                          @NotNull RegisterLayout layout, @NotNull RegisterState state,
	                                          boolean deadStore) {
		if (deadStore) {
			state.setWord(instruction.register());
			return;
		}

		if (instruction.value() == 0) {
			state.setZero(instruction.register());
			return;
		}

		if (state.canDeferConstants()) {
			state.setWordConstant(instruction.register(), instruction.value());
			return;
		}

		pushInt(mv, instruction.value());
		storeWordRegister(mv, layout, state, instruction.register());
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Const instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitConstStringInstruction(@NotNull MethodVisitor mv,
	                                                @NotNull ConstStringInstruction instruction,
	                                                @NotNull RegisterLayout layout,
	                                                @NotNull RegisterState state) {
		if (state.canDeferConstants()) {
			state.setReferenceConstant(instruction.register(), STRING_TYPE, new LdcReferenceValue(instruction.string()));
			return;
		}

		mv.visitLdcInsn(instruction.string());
		storeReferenceRegister(mv, layout, state, instruction.register(), STRING_TYPE);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Const instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitConstTypeInstruction(@NotNull MethodVisitor mv,
	                                              @NotNull ConstTypeInstruction instruction,
	                                              @NotNull RegisterLayout layout,
	                                              @NotNull RegisterState state) {
		Type constant = asmType(instruction.type());
		if (state.canDeferConstants()) {
			state.setReferenceConstant(instruction.register(), CLASS_TYPE, new LdcReferenceValue(constant));
			return;
		}

		mv.visitLdcInsn(constant);
		storeReferenceRegister(mv, layout, state, instruction.register(), CLASS_TYPE);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Const instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitConstWideInstruction(@NotNull MethodVisitor mv,
	                                              @NotNull ConstWideInstruction instruction,
	                                              @NotNull RegisterLayout layout,
	                                              @NotNull RegisterState state) {
		pushLong(mv, instruction.value());
		storeWideRegister(mv, layout, state, instruction.register());
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Fill array instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitFillArrayDataInstruction(@NotNull MethodVisitor mv, @NotNull FillArrayDataInstruction instruction,
	                                                  @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		ReferenceType referenceType = state.referenceType(instruction.array());
		if (!(referenceType instanceof ArrayType arrayType))
			throw new UnsupportedOperationException("fill-array-data requires a known array type");

		ClassType elementType = arrayType.componentType();
		byte[] data = instruction.data();
		int width = instruction.elementSize();
		if (data.length % width != 0)
			throw new IllegalArgumentException("Malformed fill-array-data payload");

		int elements = data.length / width;
		for (int i = 0; i < elements; i++) {
			loadReferenceRegister(mv, layout, state, instruction.array());
			pushInt(mv, i);
			pushFilledArrayElement(mv, elementType, data, width, i);
			mv.visitInsn(arrayStoreOpcode(elementType));
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Const instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 *
	 * @return Result containing the array type of the filled-new-array instruction.
	 */
	private static PendingResult visitFilledNewArrayInstruction(@NotNull MethodVisitor mv,
	                                                            @NotNull FilledNewArrayInstruction instruction,
	                                                            @NotNull RegisterLayout layout,
	                                                            @NotNull RegisterState state) {
		ClassType arrayType = normalizeArrayType(instruction.componentType());
		ClassType elementType = ConversionSupport.arrayElementType(arrayType);
		if (isWideType(elementType))
			throw new UnsupportedOperationException("filled-new-array with wide components is not supported");

		pushInt(mv, filledNewArrayRegisterCount(instruction));
		ReferenceType resultType = emitNewArray(mv, arrayType);
		int[] registers = filledNewArrayRegisters(instruction);
		for (int i = 0; i < registers.length; i++) {
			mv.visitInsn(DUP);
			pushInt(mv, i);
			loadRegisterForType(mv, layout, state, registers[i], elementType);
			mv.visitInsn(arrayStoreOpcode(elementType));
		}

		return new PendingResult(resultType);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Field instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitInstanceFieldInstruction(@NotNull MethodVisitor mv, @NotNull InstanceFieldInstruction instruction,
	                                                  @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		String owner = instruction.owner().internalName();
		String descriptor = instruction.type().descriptor();
		boolean isGet = instruction.opcode() < Opcodes.IPUT;
		if (isGet) {
			loadReferenceRegister(mv, layout, state, instruction.instance());
			mv.visitFieldInsn(GETFIELD, owner, instruction.name(), descriptor);
			storeRegisterForType(mv, layout, state, instruction.value(), instruction.type());
			return;
		}

		loadReferenceRegister(mv, layout, state, instruction.instance());
		loadRegisterForType(mv, layout, state, instruction.value(), instruction.type());
		mv.visitFieldInsn(PUTFIELD, owner, instruction.name(), descriptor);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		InstanceOf instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitInstanceOfInstruction(@NotNull MethodVisitor mv, @NotNull InstanceOfInstruction instruction,
	                                               @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		loadReferenceRegister(mv, layout, state, instruction.register());
		mv.visitTypeInsn(INSTANCEOF, asmTypeOperand(instruction.type()));
		storeBooleanRegister(mv, layout, state, instruction.destination());
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Custom invoke instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static @Nullable PendingResult visitInvokeCustomInstruction(@NotNull MethodVisitor mv,
	                                                                    @NotNull InvokeCustomInstruction instruction,
	                                                                    @NotNull RegisterLayout layout,
	                                                                    @NotNull RegisterState state) {
		loadInvokeArguments(mv, layout, state, instruction.type(), instruction.argumentRegisters(),
				instruction.isRange(), instruction.first(), false);
		mv.visitInvokeDynamicInsn(
				instruction.name(),
				instruction.type().descriptor(),
				asmHandle(instruction.handle()),
				bootstrapArguments(instruction.arguments())
		);

		ClassType returnType = instruction.type().returnType();
		return isVoidType(returnType) ? null : new PendingResult(returnType);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Invoke instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static @Nullable PendingResult visitInvokeInstruction(@NotNull MethodVisitor mv, @NotNull InvokeInstruction instruction,
	                                                              @NotNull RegisterLayout layout,
	                                                              @NotNull RegisterState state) {
		DeferredReferenceValue deferredReferenceValue = tryDeferReferenceResult(instruction, state);
		if (deferredReferenceValue != null)
			return new PendingResult(instruction.type().returnType(), deferredReferenceValue);

		boolean hasReceiver = instruction.opcode() != Invoke.STATIC;
		loadInvokeArguments(mv, layout, state, instruction.type(), instruction.arguments(),
				instruction.isRange(), instruction.first(), hasReceiver);

		int opcode = switch (instruction.opcode()) {
			case Invoke.VIRTUAL, Invoke.POLYMORPHIC -> INVOKEVIRTUAL;
			case Invoke.INTERFACE -> INVOKEINTERFACE;
			case Invoke.DIRECT, Invoke.SUPER -> INVOKESPECIAL;
			case Invoke.STATIC -> INVOKESTATIC;
			default -> throw new IllegalArgumentException("Unsupported invoke kind: " + instruction.opcode());
		};

		mv.visitMethodInsn(opcode, asmOwner(instruction.owner()), instruction.name(),
				instruction.type().descriptor(), instruction.opcode() == Invoke.INTERFACE);

		ClassType returnType = instruction.type().returnType();
		return isVoidType(returnType) ? null : new PendingResult(returnType);
	}

	/**
	 * @param instruction
	 * 		Invoke instruction to check for being a deferable reference constant.
	 * @param state
	 * 		Current register state.
	 *
	 * @return A deferred reference value if the instruction can be deferred, or {@code null} otherwise.
	 */
	private static @Nullable DeferredReferenceValue tryDeferReferenceResult(@NotNull InvokeInstruction instruction,
	                                                                        @NotNull RegisterState state) {
		// Only certain static invokes can be deferred as reference constants,
		// and we only want to do this when we're in a state where deferring constants is allowed.
		if (!state.canDeferConstants()) return null;
		if (instruction.opcode() != Invoke.STATIC) return null;

		// We can defer certain invocations of valueOf methods on boxed primitive wrapper types as reference constants,
		// treating the result as if it were a normal boxed constant value.
		if (!"valueOf".equals(instruction.name())) return null;

		MethodType type = instruction.type();
		if (type.parameterTypes().size() != 1) return null;
		if (!(type.returnType() instanceof ReferenceType)) return null;

		// Check for boxed primitive wrapper types by looking for a single parameter of a primitive type, and return null if it's not there.
		ClassType parameterType = type.parameterTypes().getFirst();
		String parameterDescriptor = parameterType.descriptor();
		if (!"Z".equals(parameterDescriptor)
				&& !"B".equals(parameterDescriptor)
				&& !"C".equals(parameterDescriptor)
				&& !"S".equals(parameterDescriptor)
				&& !"I".equals(parameterDescriptor)) {
			return null;
		}

		// Check that the argument is a known constant.
		int argumentRegister = instruction.isRange() ? instruction.first() : instruction.arguments()[0];
		Integer constant = state.kind(argumentRegister) == RegisterKind.ZERO
				? Integer.valueOf(0)
				: state.wordConstant(argumentRegister);
		if (constant == null)
			return null; // Not a known constant, so we can't defer.

		return new DeferredBoxedWordValue(asmOwner(instruction.owner()), type.descriptor(), constant);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Monitor instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitMonitorInstruction(@NotNull MethodVisitor mv, @NotNull MonitorInstruction instruction,
	                                            @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		loadReferenceRegister(mv, layout, state, instruction.register());
		mv.visitInsn(instruction.exit() ? MONITOREXIT : MONITORENTER);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Move instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitMoveInstruction(@NotNull MethodVisitor mv, @NotNull MoveInstruction instruction,
	                                         @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		// Try to optimize moves of constants by just updating the register state without emitting any bytecode,
		// if we're in a state where deferring constants is allowed.
		if (state.canDeferConstants()) {
			// For ZERO registers, we can just mark the destination register as ZERO as well,
			// since they are effectively the same constant value.
			if (state.kind(instruction.from()) == RegisterKind.ZERO) {
				state.setZero(instruction.to());
				return;
			}

			// For other constant values, if the source register is a known constant,
			// we can just set the destination register to have the same constant value without emitting any bytecode.
			Integer constant = state.wordConstant(instruction.from());
			if (constant != null) {
				state.setWordConstant(instruction.to(), constant);
				return;
			}
		}

		// Otherwise, this is a normal move that needs to be emitted.
		// Load the value from the source register and store it into the destination register.
		loadWordRegister(mv, layout, state, instruction.from());
		if (state.isBooleanWord(instruction.from())) {
			storeBooleanRegister(mv, layout, state, instruction.to());
		} else {
			storeWordRegister(mv, layout, state, instruction.to());
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Move instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitMoveObjectInstruction(@NotNull MethodVisitor mv, @NotNull MoveObjectInstruction instruction,
	                                               @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		// Same idea as the other move instruction method, but for reference registers.
		if (state.canDeferConstants()) {
			if (state.kind(instruction.from()) == RegisterKind.ZERO) {
				state.setZero(instruction.to());
				return;
			}
			DeferredReferenceValue constant = state.referenceConstant(instruction.from());
			if (constant != null) {
				state.setReferenceConstant(instruction.to(), state.referenceType(instruction.from()), constant);
				return;
			}
		}

		loadReferenceRegister(mv, layout, state, instruction.from());
		storeReferenceRegister(mv, layout, state, instruction.to(), state.referenceType(instruction.from()));
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Move instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitMoveWideInstruction(@NotNull MethodVisitor mv, @NotNull MoveWideInstruction instruction,
	                                             @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		// Unlike the other move instructions, there are no constants that we can defer for wide registers.
		loadWideRegister(mv, layout, instruction.from());
		storeWideRegister(mv, layout, state, instruction.to());
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		New array instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitNewArrayInstruction(@NotNull MethodVisitor mv, @NotNull NewArrayInstruction instruction,
	                                             @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		loadWordRegister(mv, layout, state, instruction.sizeRegister());
		ReferenceType resultType = emitNewArray(mv, normalizeArrayType(instruction.componentType()));
		storeReferenceRegister(mv, layout, state, instruction.dest(), resultType);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Packed-switch instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitPackedSwitchInstruction(@NotNull MethodVisitor mv, @NotNull PackedSwitchInstruction instruction,
	                                                 @NotNull RegisterLayout layout, @NotNull RegisterState state,
	                                                 @NotNull BiFunction<Label, String, org.objectweb.asm.Label> lbl) {
		state.materializeConstants(mv, layout, false, true);
		org.objectweb.asm.Label defaultLabel = new org.objectweb.asm.Label();
		loadWordRegister(mv, layout, state, instruction.register());
		org.objectweb.asm.Label[] labels = instruction.targets().stream()
				.map(target -> lbl.apply(target, "packed-switch"))
				.toArray(org.objectweb.asm.Label[]::new);
		mv.visitTableSwitchInsn(instruction.firstKey(), instruction.firstKey() + labels.length - 1, defaultLabel, labels);
		mv.visitLabel(defaultLabel);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Sparse-switch instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitSparseSwitchInstruction(@NotNull MethodVisitor mv, @NotNull SparseSwitchInstruction instruction,
	                                                 @NotNull RegisterLayout layout, @NotNull RegisterState state,
	                                                 @NotNull BiFunction<Label, String, org.objectweb.asm.Label> lbl) {
		state.materializeConstants(mv, layout, false, true);
		org.objectweb.asm.Label defaultLabel = new org.objectweb.asm.Label();
		loadWordRegister(mv, layout, state, instruction.register());
		var keys = instruction.targets().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
		int[] caseKeys = new int[keys.size()];
		org.objectweb.asm.Label[] caseLabels = new org.objectweb.asm.Label[keys.size()];
		for (int i = 0; i < keys.size(); i++) {
			caseKeys[i] = keys.get(i).getKey();
			caseLabels[i] = lbl.apply(keys.get(i).getValue(), "sparse-switch");
		}
		mv.visitLookupSwitchInsn(defaultLabel, caseKeys, caseLabels);
		mv.visitLabel(defaultLabel);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Field instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitStaticFieldInstruction(@NotNull MethodVisitor mv, @NotNull StaticFieldInstruction instruction,
	                                                @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		String owner = instruction.owner().internalName();
		String descriptor = instruction.type().descriptor();
		boolean isGet = instruction.opcode() < Opcodes.SPUT;
		if (isGet) {
			mv.visitFieldInsn(GETSTATIC, owner, instruction.name(), descriptor);
			storeRegisterForType(mv, layout, state, instruction.value(), instruction.type());
			return;
		}

		loadRegisterForType(mv, layout, state, instruction.value(), instruction.type());
		mv.visitFieldInsn(PUTSTATIC, owner, instruction.name(), descriptor);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Throw instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitThrowInstruction(@NotNull MethodVisitor mv, @NotNull ThrowInstruction instruction,
	                                          @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		loadReferenceRegister(mv, layout, state, instruction.value());
		mv.visitInsn(ATHROW);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Return instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitReturnInstruction(@NotNull MethodVisitor mv, @NotNull MethodType methodType,
	                                           @NotNull ReturnInstruction instruction,
	                                           @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		ClassType returnType = methodType.returnType();
		switch (instruction.opcode()) {
			case Opcodes.RETURN_VOID -> mv.visitInsn(RETURN);
			case Opcodes.RETURN_OBJECT -> {
				loadReferenceRegister(mv, layout, state, instruction.register());
				mv.visitInsn(ARETURN);
			}
			case Opcodes.RETURN_WIDE -> {
				if (isDoubleType(returnType)) {
					loadWideAsDouble(mv, layout, instruction.register());
					mv.visitInsn(DRETURN);
				} else {
					loadWideRegister(mv, layout, instruction.register());
					mv.visitInsn(LRETURN);
				}
			}
			case Opcodes.RETURN -> {
				if (isFloatType(returnType)) {
					loadWordAsFloat(mv, layout, state, instruction.register());
					mv.visitInsn(FRETURN);
				} else {
					loadWordRegister(mv, layout, state, instruction.register());
					mv.visitInsn(IRETURN);
				}
			}
			default ->
					throw new IllegalArgumentException("Unsupported return opcode: 0x" + Integer.toHexString(instruction.opcode()));
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Unary instruction to write.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void visitUnaryInstruction(@NotNull MethodVisitor mv, @NotNull UnaryInstruction instruction,
	                                          @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		switch (instruction.opcode()) {
			case Opcodes.NEG_INT -> {
				loadWordRegister(mv, layout, state, instruction.source());
				mv.visitInsn(INEG);
				storeWordRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.NOT_INT -> {
				loadWordRegister(mv, layout, state, instruction.source());
				mv.visitInsn(ICONST_M1);
				mv.visitInsn(IXOR);
				storeWordRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.NEG_LONG -> {
				loadWideRegister(mv, layout, instruction.source());
				mv.visitInsn(LNEG);
				storeWideRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.NOT_LONG -> {
				loadWideRegister(mv, layout, instruction.source());
				pushLong(mv, -1L);
				mv.visitInsn(LXOR);
				storeWideRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.NEG_FLOAT -> {
				loadWordAsFloat(mv, layout, state, instruction.source());
				mv.visitInsn(FNEG);
				storeFloatRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.NEG_DOUBLE -> {
				loadWideAsDouble(mv, layout, instruction.source());
				mv.visitInsn(DNEG);
				storeDoubleRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.INT_TO_LONG -> {
				loadWordRegister(mv, layout, state, instruction.source());
				mv.visitInsn(I2L);
				storeWideRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.INT_TO_FLOAT -> {
				loadWordRegister(mv, layout, state, instruction.source());
				mv.visitInsn(I2F);
				storeFloatRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.INT_TO_DOUBLE -> {
				loadWordRegister(mv, layout, state, instruction.source());
				mv.visitInsn(I2D);
				storeDoubleRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.LONG_TO_INT -> {
				loadWideRegister(mv, layout, instruction.source());
				mv.visitInsn(L2I);
				storeWordRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.LONG_TO_FLOAT -> {
				loadWideRegister(mv, layout, instruction.source());
				mv.visitInsn(L2F);
				storeFloatRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.LONG_TO_DOUBLE -> {
				loadWideRegister(mv, layout, instruction.source());
				mv.visitInsn(L2D);
				storeDoubleRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.FLOAT_TO_INT -> {
				loadWordAsFloat(mv, layout, state, instruction.source());
				mv.visitInsn(F2I);
				storeWordRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.FLOAT_TO_LONG -> {
				loadWordAsFloat(mv, layout, state, instruction.source());
				mv.visitInsn(F2L);
				storeWideRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.FLOAT_TO_DOUBLE -> {
				loadWordAsFloat(mv, layout, state, instruction.source());
				mv.visitInsn(F2D);
				storeDoubleRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.DOUBLE_TO_INT -> {
				loadWideAsDouble(mv, layout, instruction.source());
				mv.visitInsn(D2I);
				storeWordRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.DOUBLE_TO_LONG -> {
				loadWideAsDouble(mv, layout, instruction.source());
				mv.visitInsn(D2L);
				storeWideRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.DOUBLE_TO_FLOAT -> {
				loadWideAsDouble(mv, layout, instruction.source());
				mv.visitInsn(D2F);
				storeFloatRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.INT_TO_BYTE -> {
				loadWordRegister(mv, layout, state, instruction.source());
				mv.visitInsn(I2B);
				storeWordRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.INT_TO_CHAR -> {
				loadWordRegister(mv, layout, state, instruction.source());
				mv.visitInsn(I2C);
				storeWordRegister(mv, layout, state, instruction.dest());
			}
			case Opcodes.INT_TO_SHORT -> {
				loadWordRegister(mv, layout, state, instruction.source());
				mv.visitInsn(I2S);
				storeWordRegister(mv, layout, state, instruction.dest());
			}
			default ->
					throw new IllegalArgumentException("Unsupported unary opcode: 0x" + Integer.toHexString(instruction.opcode()));
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param methodType
	 * 		Method type of the invoked method.
	 * @param registers
	 * 		Argument registers for the invoke instruction.
	 * @param range
	 * 		Whether the invoke instruction is in range form.
	 * @param firstRegister
	 * 		First register for the invoke instruction (only used if range is true).
	 * @param hasReceiver
	 * 		Whether the invoke instruction has a receiver argument (i.e. is not a static invoke).
	 */
	private static void loadInvokeArguments(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                        @NotNull RegisterState state, @NotNull MethodType methodType,
	                                        int[] registers, boolean range, int firstRegister,
	                                        boolean hasReceiver) {
		int cursor = 0;
		if (hasReceiver) {
			int receiverRegister = range ? firstRegister : registers[cursor];
			loadReferenceRegister(mv, layout, state, receiverRegister);
			cursor++;
		}

		for (ClassType parameterType : methodType.parameterTypes()) {
			int register = range ? firstRegister + cursor : registers[cursor];
			loadRegisterForType(mv, layout, state, register, parameterType);
			cursor += slotSize(parameterType);
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param instruction
	 * 		Move-result instruction to write.
	 * @param pending
	 * 		Optional pending result from the preceding invoke instruction, if any.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 *
	 * @return {@code null} as the result is always consumed. Exists for calling context usage.
	 */
	private static @Nullable PendingResult consumePendingResult(@NotNull MethodVisitor mv,
	                                                            @NotNull MoveResultInstruction instruction,
	                                                            @Nullable PendingResult pending,
	                                                            @NotNull RegisterLayout layout,
	                                                            @NotNull RegisterState state) {
		if (pending == null)
			throw new IllegalStateException("move-result without a pending producer");

		ClassType type = pending.type();
		switch (instruction.type()) {
			case Result.NORMAL -> {
				if (isWideType(type) || isReferenceType(type))
					throw new IllegalStateException("move-result type mismatch");
			}
			case Result.WIDE -> {
				if (!isWideType(type))
					throw new IllegalStateException("move-result-wide type mismatch");
			}
			case Result.OBJECT -> {
				if (!isReferenceType(type))
					throw new IllegalStateException("move-result-object type mismatch");
			}
			default -> throw new IllegalArgumentException("Unsupported move-result type: " + instruction.type());
		}

		if (pending.deferredReferenceValue() != null) {
			state.setReferenceConstant(instruction.to(), asReferenceType(type), pending.deferredReferenceValue());
			return null;
		}

		if (isBooleanType(type)) {
			storeBooleanRegister(mv, layout, state, instruction.to());
		} else {
			storeRegisterForType(mv, layout, state, instruction.to(), type);
		}
		return null;
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param pending
	 * 		Optional pending result to flush if it hasn't been consumed by a move-result instruction.
	 *
	 * @return {@code null} as the result is always flushed. Exists for calling context usage.
	 */
	private static @Nullable PendingResult flushPendingResult(@NotNull MethodVisitor mv, @Nullable PendingResult pending) {
		if (pending == null)
			return null;
		if (pending.deferredReferenceValue() != null)
			return null;
		mv.visitInsn(isWideType(pending.type()) ? POP2 : POP);
		return null;
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param method
	 * 		Method member being written.
	 * @param code
	 * 		Code object containing the method's instructions and register information.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 */
	private static void initializeParameters(@NotNull MethodVisitor mv, @NotNull MethodMember method, @NotNull Code code,
	                                         @NotNull RegisterLayout layout, @NotNull RegisterState state) {
		int targetRegister = code.getRegisters() - code.getIn();
		int sourceLocal = 0;

		// If the method is not static, the first parameter is the receiver, which is always a reference type.
		if ((method.getAccess() & ACC_STATIC) == 0) {
			ReferenceType ownerType = method.getOwner() == null ? Types.OBJECT : method.getOwner();
			if (layout.ref(targetRegister) == sourceLocal) {
				state.setReference(targetRegister, ownerType);
			} else {
				mv.visitVarInsn(ALOAD, sourceLocal);
				storeReferenceRegister(mv, layout, state, targetRegister, ownerType);
			}
			sourceLocal++;
			targetRegister++;
		}

		// For the rest of the parameters, load them from their corresponding local variable slots and store them
		// into their assigned registers, while also updating the register state with their types.
		for (ClassType parameterType : method.getType().parameterTypes()) {
			if (parameterType instanceof ReferenceType referenceType) {
				if (layout.ref(targetRegister) == sourceLocal) {
					state.setReference(targetRegister, referenceType);
				} else {
					mv.visitVarInsn(ALOAD, sourceLocal);
					storeReferenceRegister(mv, layout, state, targetRegister, referenceType);
				}
				sourceLocal++;
			} else if (isDoubleType(parameterType)) {
				mv.visitVarInsn(DLOAD, sourceLocal);
				sourceLocal += 2;
				storeDoubleRegister(mv, layout, state, targetRegister);
			} else if (isLongType(parameterType)) {
				if (layout.wide(targetRegister) == sourceLocal) {
					state.setWide(targetRegister);
				} else {
					mv.visitVarInsn(LLOAD, sourceLocal);
					storeWideRegister(mv, layout, state, targetRegister);
				}
				sourceLocal += 2;
			} else if (isFloatType(parameterType)) {
				mv.visitVarInsn(FLOAD, sourceLocal++);
				storeFloatRegister(mv, layout, state, targetRegister);
			} else {
				if (layout.word(targetRegister) == sourceLocal) {
					if (isBooleanType(parameterType)) {
						state.setBoolean(targetRegister);
					} else {
						state.setWord(targetRegister);
					}
				} else {
					mv.visitVarInsn(ILOAD, sourceLocal);
					if (isBooleanType(parameterType)) {
						storeBooleanRegister(mv, layout, state, targetRegister);
					} else {
						storeWordRegister(mv, layout, state, targetRegister);
					}
				}
				sourceLocal++;
			}
			targetRegister += slotSize(parameterType);
		}
	}

	/**
	 * @param method
	 * 		Method member to calculate parameter register slots for.
	 *
	 * @return Number of register slots needed to hold the parameters of the given method, including the receiver if it's not static.
	 */
	private static int parameterSlots(@NotNull MethodMember method) {
		int slots = (method.getAccess() & ACC_STATIC) == 0 ? 1 : 0;
		for (ClassType parameterType : method.getType().parameterTypes()) {
			slots += slotSize(parameterType);
		}
		return slots;
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to load.
	 * @param type
	 * 		Type of the value in the register, used to determine how to load it.
	 */
	private static void loadRegisterForType(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                        @NotNull RegisterState state, int register, @NotNull ClassType type) {
		if (type instanceof ReferenceType) {
			loadReferenceRegister(mv, layout, state, register);
		} else if (isDoubleType(type)) {
			loadWideAsDouble(mv, layout, register);
		} else if (isLongType(type)) {
			loadWideRegister(mv, layout, register);
		} else if (isFloatType(type)) {
			loadWordAsFloat(mv, layout, state, register);
		} else {
			loadWordRegister(mv, layout, state, register);
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to store.
	 * @param type
	 * 		Type of the value in the register, used to determine how to store it.
	 */
	private static void storeRegisterForType(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                         @NotNull RegisterState state, int register, @NotNull ClassType type) {
		if (type instanceof ReferenceType referenceType) {
			storeReferenceRegister(mv, layout, state, register, referenceType);
		} else if (isBooleanType(type)) {
			storeBooleanRegister(mv, layout, state, register);
		} else if (isDoubleType(type)) {
			storeDoubleRegister(mv, layout, state, register);
		} else if (isLongType(type)) {
			storeWideRegister(mv, layout, state, register);
		} else if (isFloatType(type)) {
			storeFloatRegister(mv, layout, state, register);
		} else {
			storeWordRegister(mv, layout, state, register);
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to load.
	 */
	private static void loadWordRegister(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                     @NotNull RegisterState state, int register) {
		if (state.kind(register) == RegisterKind.ZERO) {
			mv.visitInsn(ICONST_0);
		} else if (state.wordConstant(register) != null) {
			pushInt(mv, state.wordConstant(register));
		} else {
			mv.visitVarInsn(ILOAD, layout.word(register));
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to store.
	 */
	private static void storeWordRegister(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                      @NotNull RegisterState state, int register) {
		mv.visitVarInsn(ISTORE, layout.word(register));
		state.setWord(register);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to store.
	 */
	private static void storeBooleanRegister(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                         @NotNull RegisterState state, int register) {
		mv.visitVarInsn(ISTORE, layout.word(register));
		state.setBoolean(register);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param register
	 * 		Register to load.
	 */
	private static void loadWideRegister(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout, int register) {
		mv.visitVarInsn(LLOAD, layout.wide(register));
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to store.
	 */
	private static void storeWideRegister(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                      @NotNull RegisterState state, int register) {
		mv.visitVarInsn(LSTORE, layout.wide(register));
		state.setWide(register);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to load.
	 */
	private static void loadReferenceRegister(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                          @NotNull RegisterState state, int register) {
		if (state.kind(register) == RegisterKind.ZERO) {
			mv.visitInsn(ACONST_NULL);
		} else if (state.referenceConstant(register) != null) {
			state.referenceConstant(register).emit(mv);
		} else {
			mv.visitVarInsn(ALOAD, layout.ref(register));
		}
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to store.
	 */
	private static void storeReferenceRegister(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                           @NotNull RegisterState state, int register,
	                                           @Nullable ReferenceType type) {
		mv.visitVarInsn(ASTORE, layout.ref(register));
		state.setReference(register, type);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to load. Contains the int bits of the float value to load.
	 */
	private static void loadWordAsFloat(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                    @NotNull RegisterState state, int register) {
		loadWordRegister(mv, layout, state, register);
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to store. Will contain the int bits of the float value to store.
	 */
	private static void storeFloatRegister(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                       @NotNull RegisterState state, int register) {
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false);
		storeWordRegister(mv, layout, state, register);
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param register
	 * 		Register to load. Contains the long bits of the double value to load.
	 */
	private static void loadWideAsDouble(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout, int register) {
		loadWideRegister(mv, layout, register);
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
	}

	/**
	 *
	 * @param mv
	 * 		Method writing visitor.
	 * @param layout
	 * 		Register layout for the method.
	 * @param state
	 * 		Current register state for the method.
	 * @param register
	 * 		Register to store. Will contain the long bits of the double value to store.
	 */
	private static void storeDoubleRegister(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
	                                        @NotNull RegisterState state, int register) {
		mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
		storeWideRegister(mv, layout, state, register);
	}

	/**
	 * @param state
	 * 		Current register state for the method.
	 * @param left
	 * 		Left register of the comparison.
	 * @param right
	 * 		Right register of the comparison.
	 *
	 * @return {@code true} if the comparison between the two registers should be done using reference comparison semantics,
	 * {@code false} if it should be done using value comparison semantics.
	 */
	private static boolean usesReferenceCompare(@NotNull RegisterState state, int left, int right) {
		return state.kind(left) == RegisterKind.REFERENCE
				|| state.kind(right) == RegisterKind.REFERENCE
				|| (state.kind(left) == RegisterKind.ZERO && state.kind(right) == RegisterKind.REFERENCE)
				|| (state.kind(right) == RegisterKind.ZERO && state.kind(left) == RegisterKind.REFERENCE);
	}

	/**
	 * @param instruction
	 * 		New array instruction to extract registers from.
	 *
	 * @return Array of registers used by the given filled-new-array instruction, in order.
	 */
	private static int[] filledNewArrayRegisters(@NotNull FilledNewArrayInstruction instruction) {
		if (!instruction.isRange()) {
			return instruction.registers();
		}
		int[] registers = new int[filledNewArrayRegisterCount(instruction)];
		for (int i = 0; i < registers.length; i++) {
			registers[i] = instruction.first() + i;
		}
		return registers;
	}

	/**
	 * @param instruction
	 * 		Filled-new-array instruction to count registers for.
	 *
	 * @return Number of registers used by the given filled-new-array instruction.
	 */
	private static int filledNewArrayRegisterCount(@NotNull FilledNewArrayInstruction instruction) {
		return instruction.isRange() ? instruction.last() - instruction.first() + 1 : instruction.registers().length;
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param declaredArrayType
	 * 		Declared array type of the new array instruction.
	 *
	 * @return Reference type of the emitted array.
	 */
	private static @NotNull ReferenceType emitNewArray(@NotNull MethodVisitor mv, @NotNull ClassType declaredArrayType) {
		ClassType normalized = normalizeArrayType(declaredArrayType);
		ClassType elementType = ConversionSupport.arrayElementType(normalized);
		if (elementType instanceof PrimitiveType primitiveType) {
			mv.visitIntInsn(NEWARRAY, primitiveArrayType(primitiveType));
		} else {
			mv.visitTypeInsn(ANEWARRAY, asmTypeOperand(elementType));
		}
		return (ReferenceType) normalized;
	}

	/**
	 * @param instruction
	 * 		Array instruction to extract element type from.
	 * @param state
	 * 		Current register state for the method, used to infer the array element type if possible.
	 *
	 * @return Inferred array element type for the given array instruction.
	 */
	private static @NotNull ClassType arrayElementType(@NotNull ArrayInstruction instruction, @NotNull RegisterState state) {
		ClassType inferred = state.referenceType(instruction.array()) instanceof ArrayType arrayType ? arrayType.componentType() : null;
		return switch (instruction.opcode()) {
			case Opcodes.AGET, Opcodes.APUT -> inferred != null && isFloatType(inferred) ? Types.FLOAT : Types.INT;
			case Opcodes.AGET_WIDE, Opcodes.APUT_WIDE ->
					inferred != null && isDoubleType(inferred) ? Types.DOUBLE : Types.LONG;
			case Opcodes.AGET_OBJECT, Opcodes.APUT_OBJECT ->
					inferred instanceof ReferenceType ? inferred : Types.OBJECT;
			case Opcodes.AGET_BOOLEAN, Opcodes.APUT_BOOLEAN -> Types.BOOLEAN;
			case Opcodes.AGET_BYTE, Opcodes.APUT_BYTE -> Types.BYTE;
			case Opcodes.AGET_CHAR, Opcodes.APUT_CHAR -> Types.CHAR;
			case Opcodes.AGET_SHORT, Opcodes.APUT_SHORT -> Types.SHORT;
			default ->
					throw new IllegalArgumentException("Unsupported array opcode: 0x" + Integer.toHexString(instruction.opcode()));
		};
	}

	/**
	 * @param mv
	 * 		Method writing visitor.
	 * @param elementType
	 * 		Array element type of the filled-new-array instruction.
	 * @param data
	 * 		Array of bytes containing the literal values to fill the new array with.
	 * @param width
	 * 		Width of each array element in bytes.
	 * @param index
	 * 		Index of the element to push from the data array.
	 */
	private static void pushFilledArrayElement(@NotNull MethodVisitor mv, @NotNull ClassType elementType,
	                                           byte[] data, int width, int index) {
		int offset = index * width;
		switch (elementType.descriptor()) {
			case "Z" -> pushInt(mv, data[offset] != 0 ? 1 : 0);
			case "B" -> pushInt(mv, data[offset]);
			case "C" -> pushInt(mv, readUnsignedShort(data, offset));
			case "S" -> pushInt(mv, readSignedShort(data, offset));
			case "F" -> mv.visitLdcInsn(Float.intBitsToFloat(readInt(data, offset)));
			case "J" -> pushLong(mv, readLong(data, offset));
			case "D" -> mv.visitLdcInsn(Double.longBitsToDouble(readLong(data, offset)));
			default -> pushInt(mv, readInt(data, offset));
		}
	}

	/**
	 * @param data
	 * 		Data to read from.
	 * @param offset
	 * 		Offset into the data.
	 *
	 * @return Unsigned short value at the given offset.
	 */
	private static int readUnsignedShort(byte[] data, int offset) {
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
	}

	/**
	 * @param data
	 * 		Data to read from.
	 * @param offset
	 * 		Offset into the data.
	 *
	 * @return Signed short value at the given offset.
	 */
	private static int readSignedShort(byte[] data, int offset) {
		return (short) readUnsignedShort(data, offset);
	}

	/**
	 * @param data
	 * 		Data to read from.
	 * @param offset
	 * 		Offset into the data.
	 *
	 * @return Int value at the given offset.
	 */
	private static int readInt(byte[] data, int offset) {
		return (data[offset] & 0xFF)
				| ((data[offset + 1] & 0xFF) << 8)
				| ((data[offset + 2] & 0xFF) << 16)
				| (data[offset + 3] << 24);
	}

	/**
	 * @param data
	 * 		Data to read from.
	 * @param offset
	 * 		Offset into the data.
	 *
	 * @return Long value at the given offset.
	 */
	private static long readLong(byte[] data, int offset) {
		return (data[offset] & 0xFFL)
				| ((data[offset + 1] & 0xFFL) << 8)
				| ((data[offset + 2] & 0xFFL) << 16)
				| ((data[offset + 3] & 0xFFL) << 24)
				| ((data[offset + 4] & 0xFFL) << 32)
				| ((data[offset + 5] & 0xFFL) << 40)
				| ((data[offset + 6] & 0xFFL) << 48)
				| ((data[offset + 7] & 0xFFL) << 56);
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return Java bytecode opcode.
	 */
	private static int intBinaryOpcode(int opcode) {
		return switch (opcode) {
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
			default ->
					throw new IllegalArgumentException("Unsupported int binary opcode: 0x" + Integer.toHexString(opcode));
		};
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return Java bytecode opcode.
	 */
	private static int longBinaryOpcode(int opcode) {
		return switch (opcode) {
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
			default ->
					throw new IllegalArgumentException("Unsupported long binary opcode: 0x" + Integer.toHexString(opcode));
		};
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return Java bytecode opcode.
	 */
	private static int floatBinaryOpcode(int opcode) {
		return switch (opcode) {
			case Opcodes.ADD_FLOAT -> FADD;
			case Opcodes.SUB_FLOAT -> FSUB;
			case Opcodes.MUL_FLOAT -> FMUL;
			case Opcodes.DIV_FLOAT -> FDIV;
			case Opcodes.REM_FLOAT -> FREM;
			default ->
					throw new IllegalArgumentException("Unsupported float binary opcode: 0x" + Integer.toHexString(opcode));
		};
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return Java bytecode opcode.
	 */
	private static int doubleBinaryOpcode(int opcode) {
		return switch (opcode) {
			case Opcodes.ADD_DOUBLE -> DADD;
			case Opcodes.SUB_DOUBLE -> DSUB;
			case Opcodes.MUL_DOUBLE -> DMUL;
			case Opcodes.DIV_DOUBLE -> DDIV;
			case Opcodes.REM_DOUBLE -> DREM;
			default ->
					throw new IllegalArgumentException("Unsupported double binary opcode: 0x" + Integer.toHexString(opcode));
		};
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return {@code true} when the opcode is in the binary int operation range.
	 */
	private static boolean isIntBinary(int opcode) {
		return opcode >= Opcodes.ADD_INT && opcode <= Opcodes.USHR_INT;
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return {@code true} when the opcode is in the binary long operation range.
	 */
	private static boolean isLongBinary(int opcode) {
		return opcode >= Opcodes.ADD_LONG && opcode <= Opcodes.USHR_LONG;
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return {@code true} when the opcode is in the binary float operation range.
	 */
	private static boolean isFloatBinary(int opcode) {
		return opcode >= Opcodes.ADD_FLOAT && opcode <= Opcodes.REM_FLOAT;
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return {@code true} when the opcode is in the binary int to address operation range.
	 */
	private static boolean isIntBinary2Addr(int opcode) {
		return opcode >= Opcodes.ADD_INT_2ADDR && opcode <= Opcodes.USHR_INT_2ADDR;
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return {@code true} when the opcode is in the binary long to address operation range.
	 */
	private static boolean isLongBinary2Addr(int opcode) {
		return opcode >= Opcodes.ADD_LONG_2ADDR && opcode <= Opcodes.USHR_LONG_2ADDR;
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return {@code true} when the opcode is in the binary float to address operation range.
	 */
	private static boolean isFloatBinary2Addr(int opcode) {
		return opcode >= Opcodes.ADD_FLOAT_2ADDR && opcode <= Opcodes.REM_FLOAT_2ADDR;
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return {@code true} when the opcode is a long shift.
	 */
	private static boolean isLongShift(int opcode) {
		return opcode == Opcodes.SHL_LONG || opcode == Opcodes.SHR_LONG || opcode == Opcodes.USHR_LONG;
	}

	/**
	 * @param opcode
	 * 		Dex opcode.
	 *
	 * @return {@code true} when the opcode is a long shift.
	 */
	private static boolean isLongShift2Addr(int opcode) {
		return opcode == Opcodes.SHL_LONG_2ADDR || opcode == Opcodes.SHR_LONG_2ADDR || opcode == Opcodes.USHR_LONG_2ADDR;
	}

	/**
	 * Register layout for a method, which calculates the mapping of dex registers to JVM local variable slots for each register category (word, wide, reference).
	 */
	private static final class RegisterLayout {
		private final int[] wordSlots;
		private final int[] wideSlots;
		private final int[] refSlots;

		private RegisterLayout(@NotNull MethodMember method, @NotNull Code code) {
			int registers = code.getRegisters();
			int parameterSlots = parameterSlots(method);
			this.wordSlots = new int[registers];
			this.wideSlots = new int[registers];
			this.refSlots = new int[registers];

			for (int register = 0; register < registers; register++) {
				wordSlots[register] = parameterSlots + register;
				wideSlots[register] = parameterSlots + registers + (register * 2);
				refSlots[register] = parameterSlots + registers + (registers * 2) + register;
			}

			int dexRegister = registers - code.getIn();
			int local = 0;
			if ((method.getAccess() & ACC_STATIC) == 0) {
				refSlots[dexRegister++] = local++;
			}

			for (ClassType parameterType : method.getType().parameterTypes()) {
				if (parameterType instanceof ReferenceType) {
					refSlots[dexRegister] = local;
				} else if (isLongType(parameterType)) {
					wideSlots[dexRegister] = local;
				} else if (!isFloatType(parameterType) && !isDoubleType(parameterType)) {
					wordSlots[dexRegister] = local;
				}
				local += slotSize(parameterType);
				dexRegister += slotSize(parameterType);
			}
		}

		private int word(int register) {
			return wordSlots[register];
		}

		private int wide(int register) {
			return wideSlots[register];
		}

		private int ref(int register) {
			return refSlots[register];
		}
	}

	/**
	 * Deferred reference value to be loaded into a register, used for pending invoke results that can be represented as constants.
	 */
	private interface DeferredReferenceValue {
		void emit(@NotNull MethodVisitor mv);
	}

	/**
	 * @param constant
	 * 		Constant value to load.
	 */
	private record LdcReferenceValue(@NotNull Object constant) implements DeferredReferenceValue {
		@Override
		public void emit(@NotNull MethodVisitor mv) {
			mv.visitLdcInsn(constant);
		}
	}

	/**
	 * @param owner
	 * 		Owner class of the constant value to load.
	 * @param descriptor
	 * 		Descriptor of the constant value to load.
	 * @param value
	 * 		Integer value to load, which will be boxed using the specified owner and descriptor.
	 */
	private record DeferredBoxedWordValue(@NotNull String owner, @NotNull String descriptor, int value)
			implements DeferredReferenceValue {
		@Override
		public void emit(@NotNull MethodVisitor mv) {
			pushInt(mv, value);
			mv.visitMethodInsn(INVOKESTATIC, owner, "valueOf", descriptor, false);
		}
	}

	/**
	 * @param type
	 * 		Type of the pending result.
	 * @param deferredReferenceValue
	 * 		Optional deferred reference value to load for the pending result, if it can be represented as a constant.
	 */
	private record PendingResult(@NotNull ClassType type, @Nullable DeferredReferenceValue deferredReferenceValue) {
		private PendingResult(@NotNull ClassType type) {
			this(type, null);
		}
	}

	/**
	 * Kind of value stored in a register, used for tracking the state of registers and optimizing loads and stores.
	 */
	private enum RegisterKind {
		UNKNOWN,
		WORD,
		WIDE,
		REFERENCE,
		ZERO
	}

	/**
	 * State of registers at a given point in the method, used for optimizing loads and stores by tracking the kind of value in each register,
	 * as well as any known constant values for word and reference registers.
	 */
	private static final class RegisterState {
		private final RegisterKind[] kinds;
		private final boolean[] booleanWords;
		private final ReferenceType[] referenceTypes;
		private final Integer[] wordConstants;
		private final DeferredReferenceValue[] referenceConstants;
		private final boolean deferConstants;

		private RegisterState(int registers, boolean deferConstants) {
			this.kinds = new RegisterKind[registers];
			this.booleanWords = new boolean[registers];
			this.referenceTypes = new ReferenceType[registers];
			this.wordConstants = new Integer[registers];
			this.referenceConstants = new DeferredReferenceValue[registers];
			this.deferConstants = deferConstants;
			Arrays.fill(kinds, RegisterKind.UNKNOWN);
		}

		private boolean canDeferConstants() {
			return deferConstants;
		}

		private int registerCount() {
			return kinds.length;
		}

		private RegisterKind kind(int register) {
			return kinds[register];
		}

		private @Nullable ReferenceType referenceType(int register) {
			return referenceTypes[register];
		}

		private @Nullable Integer wordConstant(int register) {
			return wordConstants[register];
		}

		private @Nullable DeferredReferenceValue referenceConstant(int register) {
			return referenceConstants[register];
		}

		private boolean isBooleanWord(int register) {
			return booleanWords[register];
		}

		private void setWord(int register) {
			kinds[register] = RegisterKind.WORD;
			booleanWords[register] = false;
			referenceTypes[register] = null;
			wordConstants[register] = null;
			referenceConstants[register] = null;
		}

		private void setBoolean(int register) {
			kinds[register] = RegisterKind.WORD;
			booleanWords[register] = true;
			referenceTypes[register] = null;
			wordConstants[register] = null;
			referenceConstants[register] = null;
		}

		private void setWide(int register) {
			kinds[register] = RegisterKind.WIDE;
			booleanWords[register] = false;
			referenceTypes[register] = null;
			wordConstants[register] = null;
			referenceConstants[register] = null;
		}

		private void setReference(int register, @Nullable ReferenceType type) {
			kinds[register] = RegisterKind.REFERENCE;
			booleanWords[register] = false;
			referenceTypes[register] = type;
			wordConstants[register] = null;
			referenceConstants[register] = null;
		}

		private void setZero(int register) {
			kinds[register] = RegisterKind.ZERO;
			booleanWords[register] = false;
			referenceTypes[register] = null;
			wordConstants[register] = null;
			referenceConstants[register] = null;
		}

		private void setWordConstant(int register, int value) {
			kinds[register] = RegisterKind.WORD;
			booleanWords[register] = false;
			referenceTypes[register] = null;
			wordConstants[register] = value;
			referenceConstants[register] = null;
		}

		private void setReferenceConstant(int register, @Nullable ReferenceType type, @NotNull DeferredReferenceValue value) {
			kinds[register] = RegisterKind.REFERENCE;
			booleanWords[register] = false;
			referenceTypes[register] = type;
			wordConstants[register] = null;
			referenceConstants[register] = value;
		}

		private void materializeConstants(@NotNull MethodVisitor mv, @NotNull RegisterLayout layout,
		                                  boolean includeConstants, boolean includeZeroes) {
			if (!deferConstants) return;

			for (int register = 0; register < kinds.length; register++) {
				if (includeConstants) {
					Integer wordConstant = wordConstants[register];
					if (wordConstant != null) {
						pushInt(mv, wordConstant);
						mv.visitVarInsn(ISTORE, layout.word(register));
					}

					DeferredReferenceValue referenceConstant = referenceConstants[register];
					if (referenceConstant != null) {
						referenceConstant.emit(mv);
						mv.visitVarInsn(ASTORE, layout.ref(register));
					}
				}

				if (includeZeroes && kinds[register] == RegisterKind.ZERO) {
					mv.visitInsn(ICONST_0);
					mv.visitVarInsn(ISTORE, layout.word(register));
					mv.visitInsn(ACONST_NULL);
					mv.visitVarInsn(ASTORE, layout.ref(register));
				}
			}
		}

		private void clearKnownConstants() {
			if (!deferConstants) return;
			Arrays.fill(wordConstants, null);
			Arrays.fill(referenceConstants, null);
		}
	}
}
