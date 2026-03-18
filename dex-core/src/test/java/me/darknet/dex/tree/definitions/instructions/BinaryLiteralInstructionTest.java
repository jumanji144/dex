package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopCCBB;
import me.darknet.dex.file.instructions.FormatBAopCCCC;
import me.darknet.dex.file.instructions.Opcodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static me.darknet.dex.util.TestUtils.EMPTY_CONTEXT;
import static me.darknet.dex.util.TestUtils.EMPTY_CONTEXT_UN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BinaryLiteralInstruction}
 */
class BinaryLiteralInstructionTest implements Opcodes {

	@Test
	void testLit8() {
		// 8-bit signed immediate values
		for (int value = Byte.MIN_VALUE; value <= Byte.MAX_VALUE; value++) {
			BinaryLiteralInstruction instr = new BinaryLiteralInstruction(0, 1, 2, (short) value);

			// Should use LIT8 opcode variant
			assertTrue(instr.opcode() >= ADD_INT_LIT8 && instr.opcode() <= USHR_INT_LIT8,
					"Opcode should be in range [ADD_INT_LIT8, USHR_INT_LIT8]");
			assertEquals(value, instr.constant(), "Decoded value should match original");
			assertEquals(2, instr.byteSize(), "LIT8 should be 2 bytes");

			// Round-trip through codec
			Format format = BinaryLiteralInstruction.CODEC.unmap(instr, EMPTY_CONTEXT_UN);
			assertInstanceOf(FormatAAopCCBB.class, format);
			BinaryLiteralInstruction decoded = BinaryLiteralInstruction.CODEC.map(format, EMPTY_CONTEXT);
			assertEquals(instr, decoded, "Codec round-trip should preserve instruction");
		}
	}

	@Test
	void testLit16() {
		// 16-bit signed immediate values
		int[] testValues = {Short.MIN_VALUE, -32768, -129, -128, 0, 127, 128, 32767, Short.MAX_VALUE};
		for (int value : testValues) {
			if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) continue; // Already tested in LIT8
			BinaryLiteralInstruction instr = new BinaryLiteralInstruction(0, 1, 2, (short) value);

			// Should use LIT16 opcode variant
			assertTrue(instr.opcode() >= ADD_INT_LIT16 && instr.opcode() <= XOR_INT_LIT16,
					"Opcode should be in range [ADD_INT_LIT8, USHR_INT_LIT8]");
			assertEquals(value, instr.constant(), "Decoded value should match original");
			assertEquals(2, instr.byteSize(), "LIT16 should be 2 bytes");

			// Round-trip through codec
			Format format = BinaryLiteralInstruction.CODEC.unmap(instr, EMPTY_CONTEXT_UN);
			assertInstanceOf(FormatBAopCCCC.class, format);
			BinaryLiteralInstruction decoded = BinaryLiteralInstruction.CODEC.map(format, EMPTY_CONTEXT);
			assertEquals(instr, decoded, "Codec round-trip should preserve instruction");
		}
	}
}