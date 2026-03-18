package me.darknet.dex.convert;

import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for analyzing try-catch blocks in dex code.
 */
public final class TryCatchSupport {
	private TryCatchSupport() {
	}

	/**
	 * @param code
	 * 		Dex code to analyze.
	 *
	 * @return List of filtered try-catch blocks that are effective, non-redundant, and non-trivial.
	 */
	public static @NotNull List<TryCatch> effectiveTryCatches(@NotNull Code code) {
		List<TryCatch> effective = new ArrayList<>(code.tryCatch().size());
		for (TryCatch tryCatch : code.tryCatch())
			if (!isRedundantRethrowTryCatch(code, tryCatch))
				effective.add(tryCatch);
		return effective;
	}

	/**
	 * @param code
	 * 		Dex code to analyze.
	 * @param tryCatch
	 * 		Try-catch block to check for redundancy.
	 *
	 * @return {@code true} when the block is a redundant rethrow, meaning it only catches all exceptions
	 * and rethrows them without any additional logic. This can be safely removed without changing the behavior of the code.
	 */
	private static boolean isRedundantRethrowTryCatch(@NotNull Code code, @NotNull TryCatch tryCatch) {
		// Check if the try-catch block has exactly one handler that catches all exceptions.
		if (tryCatch.handlers().size() != 1 || !tryCatch.handlers().getFirst().isCatchAll()) return false;

		// Check if the handler's catch block is empty.
		if (tryCatch.handlers().getFirst().handler().position() != tryCatch.end().position()) return false;

		// Check if the try block consists of a single instruction that moves the exception to a register and then throws it.
		InstructionWindow window = instructionWindow(code, tryCatch.begin().position(), tryCatch.end().position());
		if (!(window.previous() instanceof MoveExceptionInstruction(int register))) return false;
		if (!(window.single() instanceof ThrowInstruction(int value))) return false;
		if (register != value) return false;

		// Check if the handler block starts with a move-exception instruction that moves the exception to the same register.
		Instruction handlerInstruction = instructionAt(code, tryCatch.handlers().getFirst().handler().position());
		return handlerInstruction instanceof MoveExceptionInstruction;
	}

	/**
	 * @param code
	 * 		Dex code to analyze.
	 * @param startOffset
	 * 		Try-catch block start offset.
	 * @param endOffset
	 * 		Try-catch block end offset.
	 *
	 * @return Window containing instructions within the block range.
	 */
	private static @NotNull InstructionWindow instructionWindow(@NotNull Code code, int startOffset, int endOffset) {
		Instruction previous = null;
		Instruction single = null;
		int fallbackOffset = 0;
		for (Instruction instruction : code.getInstructions()) {
			if (instruction instanceof Label label) {
				fallbackOffset = label.position();
				continue;
			}
			int offset = instructionOffset(code, instruction, fallbackOffset);
			if (offset < startOffset) {
				previous = instruction;
			} else if (offset < endOffset) {
				if (single != null) return new InstructionWindow(previous, null);
				single = instruction;
			}
			if (code.offsetOf(instruction) == null) {
				fallbackOffset = offset + instruction.byteSize();
			}
		}
		return new InstructionWindow(previous, single);
	}

	/**
	 * @param code
	 * 		Dex code to analyze.
	 * @param targetOffset
	 * 		Offset to find the instruction at.
	 *
	 * @return Instruction at the specified offset, or {@code null} if no instruction starts at that offset.
	 */
	private static @Nullable Instruction instructionAt(@NotNull Code code, int targetOffset) {
		int fallbackOffset = 0;
		for (Instruction instruction : code.getInstructions()) {
			if (instruction instanceof Label label) {
				fallbackOffset = label.position();
				continue;
			}
			int offset = instructionOffset(code, instruction, fallbackOffset);
			if (offset == targetOffset) return instruction;
			if (code.offsetOf(instruction) == null) {
				fallbackOffset = offset + instruction.byteSize();
			}
		}
		return null;
	}

	private static int instructionOffset(@NotNull Code code, @NotNull Instruction instruction, int fallbackOffset) {
		Integer offset = code.offsetOf(instruction);
		return offset != null ? offset : fallbackOffset;
	}

	private record InstructionWindow(Instruction previous, Instruction single) {
	}
}
