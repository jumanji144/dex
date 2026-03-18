package me.darknet.dex.convert.util;

import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods for constructing code models for IR tests.
 */
public class IrTestUtils {
	/**
	 * @param registers
	 * 		Number of registers used by the method.
	 * @param in
	 * 		Number of registers used for incoming parameters.
	 * @param instructions
	 * 		Instruction list.
	 *
	 * @return Wrapped code model with the given instructions and register counts.
	 */
	public static @NotNull Code code(int registers, int in, Instruction... instructions) {
		Code code = new Code(in, 0, registers);
		code.addInstructions(assignLabels(List.of(instructions)));
		return code;
	}

	/**
	 * Assigns indices and positions to labels in the instruction list.
	 * This is necessary for the IR builder to correctly identify basic blocks and control flow.
	 *
	 * @param instructions
	 * 		Instruction list.
	 *
	 * @return Updated instruction list with labels having correct indices and positions.
	 */
	private static @NotNull List<Instruction> assignLabels(@NotNull List<Instruction> instructions) {
		int offset = 0;
		int index = 0;
		List<Instruction> out = new ArrayList<>(instructions.size());
		for (Instruction instruction : instructions) {
			if (instruction instanceof Label label) {
				label.index(index++);
				label.position(offset);
			} else {
				offset += instruction.byteSize();
			}
			out.add(instruction);
		}
		return out;
	}

}
