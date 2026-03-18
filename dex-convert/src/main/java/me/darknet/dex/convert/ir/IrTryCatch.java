package me.darknet.dex.convert.ir;

import me.darknet.dex.tree.definitions.code.Handler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @param startOffset
 * 		Try-catch block start offset.
 * @param endOffset
 * 		Try-catch block end offset.
 * @param handlerBlock
 * 		Handler block for the try-catch, containing the instructions to execute when an exception is caught.
 * @param handler
 * 		Handler information for the try-catch, or {@code null} if the handler block is empty (contains no instructions).
 */
public record IrTryCatch(int startOffset, int endOffset, @NotNull IrBlock handlerBlock, @Nullable Handler handler) {}
