package me.darknet.dex.convert.ir;

import me.darknet.dex.tree.definitions.code.Handler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @param handlerBlock
 * 		Block of instructions to execute when an exception is caught.
 * @param handler
 * 		Exception handler containing the exception type and handler block for the catch clause,
 * 		or {@code null} for a catch-all handler.
 */
public record IrExceptionHandler(@NotNull IrBlock handlerBlock, @Nullable Handler handler) {
}
