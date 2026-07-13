package me.darknet.dex.convert.ir;

import me.darknet.dex.convert.ir.lowering.IrLoweringEngine;
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
		IrLoweringEngine.emit(method, mv);
	}
}
