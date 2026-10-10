package me.darknet.dex.tree.definitions.debug;

import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.type.Type;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Debug information for a method.
 *
 * @param lineNumbers
 * 		List of line numbers associated with instruction labels.
 * @param addressLines
 * 		List of address lines, each containing a code-unit address and its corresponding line number.
 * @param parameterNames
 * 		List of parameter names for the method.
 * @param locals
 * 		List of local variables in the method, each with its register, name, type, signature, and start/end labels.
 */
public record DebugInformation(List<LineNumber> lineNumbers, List<AddressLine> addressLines,
                               List<String> parameterNames, List<LocalVariable> locals) {

	public DebugInformation {
		// Stable, so entries that share an address keep their stream order.
		addressLines = addressLines == null ? List.of()
				: addressLines.stream().sorted(Comparator.comparingInt(AddressLine::address)).toList();
	}

	/**
	 * The line in effect at a code-unit address: the line of the last position at or before it. This matches
	 * ART's {@code GetLineNumForPc}. A position inside an instruction does not change that instruction's line.
	 *
	 * @return the line, or {@code null} when no position is at or before {@code address}
	 */
	public @Nullable Integer lineAt(int address) {
		int low = 0;
		int high = addressLines.size() - 1;
		int found = -1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (addressLines.get(mid).address() <= address) {
				found = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return found == -1 ? null : addressLines.get(found).line();
	}

	public record LocalVariable(int register, String name, Type type, String signature, Label start, Label end) {}

	public record LineNumber(Label label, int line) {}

	public record AddressLine(int address, int line) {}

}
