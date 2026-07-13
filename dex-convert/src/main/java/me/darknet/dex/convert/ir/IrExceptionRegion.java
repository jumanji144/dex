package me.darknet.dex.convert.ir;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @param startOffset
 * 		Try-catch block start offset.
 * @param endOffset
 * 		Try-catch block end offset.
 * @param protectedBlocks
 * 		List of handler blocks for the try-catch, containing the instructions to execute when an exception is caught.
 * @param handlers
 * 		List of exception handlers for the try-catch, containing the exception types and handler blocks for each catch clause.
 */
public record IrExceptionRegion(int startOffset, int endOffset,
                                @NotNull List<IrBlock> protectedBlocks,
                                @NotNull List<IrExceptionHandler> handlers) {
	public IrExceptionRegion {
		protectedBlocks = List.copyOf(protectedBlocks);
		handlers = List.copyOf(handlers);
	}
}
