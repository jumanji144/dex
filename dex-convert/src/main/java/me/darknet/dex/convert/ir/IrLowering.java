package me.darknet.dex.convert.ir;

import me.darknet.dex.convert.ir.lowering.IrLoweringEngine;
import me.darknet.dex.convert.ir.lowering.JvmLambdaMetadata;
import me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;

/**
 * Entry point for lowering the converter's SSA-like IR to JVM bytecode.
 */
public final class IrLowering {
	private IrLowering() {}

	/**
	 * Emits {@code method} into an already-created ASM method visitor.
	 *
	 * @param method
	 * 		IR method to lower
	 * @param mv
	 * 		destination ASM visitor
	 */
	public static void emit(@NotNull IrMethod method, @NotNull MethodVisitor mv) {
		IrLoweringEngine.emit(method, mv, JvmLoweringPolicy.DETERMINISTIC_LOCAL);
	}

	/** Emits a method and returns recoverable lowering diagnostics. */
	public static @NotNull IrLoweringResult emitWithResult(@NotNull IrMethod method,
	                                                       @NotNull MethodVisitor mv) {
		return emitWithResult(method, mv, JvmLoweringPolicy.DETERMINISTIC_LOCAL);
	}

	/** Emits a method under an explicitly selected JVM lowering policy. */
	public static @NotNull IrLoweringResult emitWithResult(@NotNull IrMethod method,
	                                                       @NotNull MethodVisitor mv,
	                                                       @NotNull JvmLoweringPolicy policy) {
		return IrLoweringEngine.emitResult(method, mv, policy);
	}

	/** Emits under an explicit policy and a proof metadata index for DEX lambdas. */
	public static @NotNull IrLoweringResult emitWithResult(@NotNull IrMethod method,
	                                                       @NotNull MethodVisitor mv,
	                                                       @NotNull JvmLoweringPolicy policy,
	                                                       @NotNull JvmLambdaMetadata lambdaMetadata) {
		return IrLoweringEngine.emitResult(method, mv, policy, lambdaMetadata);
	}
}
