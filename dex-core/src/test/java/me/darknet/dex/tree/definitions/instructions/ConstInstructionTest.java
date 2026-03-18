package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.instructions.FormatBAop;
import org.junit.jupiter.api.Test;

import static me.darknet.dex.file.instructions.Opcodes.CONST_HIGH16;
import static me.darknet.dex.util.TestUtils.EMPTY_CONTEXT;
import static me.darknet.dex.util.TestUtils.EMPTY_CONTEXT_UN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConstInstruction}
 */
class ConstInstructionTest {
	@Test
	void testConst4() {
		// Test all values in the 4-bit signed range [-8, 7]
		for (int value = -8; value <= 7; value++) {
			ConstInstruction instr = new ConstInstruction(1, value);
			assertEquals(ConstInstruction.CONST_4, instr.opcode(),
					"Should use CONST_4 for 4-bit signed values");
			assertEquals(value, instr.value(), "Decoded value should match original");
			assertEquals(1, instr.byteSize(), "CONST_4 should be 1 byte");

			// Round-trip through codec
			Format format = ConstInstruction.CODEC.unmap(instr, EMPTY_CONTEXT_UN);
			assertInstanceOf(FormatBAop.class, format);
			ConstInstruction decoded = ConstInstruction.CODEC.map(format, EMPTY_CONTEXT);
			assertEquals(instr, decoded, "Codec round-trip should preserve instruction");
		}
	}

	@Test
	void testConst16() {
		// 16-bit signed immediate values
		int[] testValues = {Short.MIN_VALUE, -32768, -1, 0, 1, 32767, Short.MAX_VALUE};
		for (int value : testValues) {
			if (value >= -8 && value <= 7) continue; // Already tested in CONST_4
			ConstInstruction instr = new ConstInstruction(2, value);
			assertEquals(ConstInstruction.CONST_16, instr.opcode(),
					"Should use CONST_16 for 16-bit signed values");
			assertEquals(value, instr.value(), "Decoded value should match original");
			assertEquals(2, instr.byteSize(), "CONST_16 should be 2 bytes");

			// Round-trip through codec
			Format format = ConstInstruction.CODEC.unmap(instr, EMPTY_CONTEXT_UN);
			assertInstanceOf(FormatAAopBBBB.class, format);
			ConstInstruction decoded = ConstInstruction.CODEC.map(format, EMPTY_CONTEXT);
			assertEquals(instr, decoded, "Codec round-trip should preserve instruction");
		}
	}

	@Test
	void testConstHigh16() {
		// High 16 bits set, low 16 bits zero
		int[] testValues = {0x10000, 0x7FFF0000, 0x80000000, 0xFFFF0000};
		for (int value : testValues) {
			ConstInstruction instr = new ConstInstruction(3, value);
			assertEquals(ConstInstruction.CONST_HIGH16, instr.opcode(),
					"Should use CONST_HIGH16 for values with only high 16 bits set");
			assertEquals(value, instr.value(), "Decoded value should match original");
			assertEquals(2, instr.byteSize(), "CONST_HIGH16 should be 2 bytes");

			// Round-trip through codec
			Format format = ConstInstruction.CODEC.unmap(instr, EMPTY_CONTEXT_UN);
			assertInstanceOf(FormatAAopBBBB.class, format);
			ConstInstruction decoded = ConstInstruction.CODEC.map(format, EMPTY_CONTEXT);
			assertEquals(instr, decoded, "Codec round-trip should preserve instruction");
		}

		// The full 32-bit value should be correctly reconstructed from the high 16 bits
		ConstInstruction intHigh16 = ConstInstruction.CODEC.map(
				new FormatAAopBBBB(CONST_HIGH16, 0, 0x3f80), EMPTY_CONTEXT);
		assertEquals(0x3f800000, intHigh16.value());
	}

	@Test
	void testConst32() {
		// 32-bit signed immediate values not covered by other formats
		int[] testValues = {0x12345678, -0x12345678, 0x7FFFFFFF, 0x80000001};
		for (int value : testValues) {
			ConstInstruction instr = new ConstInstruction(4, value);
			assertEquals(ConstInstruction.CONST, instr.opcode(),
					"Should use CONST for general 32-bit values");
			assertEquals(value, instr.value(), "Decoded value should match original");
			assertEquals(4, instr.byteSize(), "CONST should be 4 bytes");

			// Round-trip through codec
			Format format = ConstInstruction.CODEC.unmap(instr, EMPTY_CONTEXT_UN);
			assertInstanceOf(FormatAAopBBBB32.class, format);
			ConstInstruction decoded = ConstInstruction.CODEC.map(format, EMPTY_CONTEXT);
			assertEquals(instr, decoded, "Codec round-trip should preserve instruction");
		}
	}

	@Test
	void testInvalidOpcodeByteSize() {
		ConstInstruction instr = new ConstInstruction(99, 0, 0);
		assertThrows(IllegalArgumentException.class, instr::byteSize);
	}
}