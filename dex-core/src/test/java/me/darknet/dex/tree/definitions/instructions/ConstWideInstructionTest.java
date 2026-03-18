package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.file.instructions.FormatAAopBBBB32;
import me.darknet.dex.file.instructions.FormatAAopBBBB64;
import org.junit.jupiter.api.Test;

import static me.darknet.dex.file.instructions.Opcodes.CONST_WIDE_HIGH16;
import static me.darknet.dex.util.TestUtils.EMPTY_CONTEXT;
import static me.darknet.dex.util.TestUtils.EMPTY_CONTEXT_UN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConstWideInstruction}
 */
class ConstWideInstructionTest {
	@Test
	void testConstWide16() {
		// 16-bit signed immediate values
		long[] testValues = {Short.MIN_VALUE, -32768, -1, 0, 1, 32767, Short.MAX_VALUE};
		for (long value : testValues) {
			ConstWideInstruction instr = new ConstWideInstruction(1, value);
			assertEquals(ConstWideInstruction.CONST_WIDE_16, instr.opcode(),
					"Should use CONST_WIDE_16 for 16-bit signed values");
			assertEquals(value, instr.value(), "Decoded value should match original");
			assertEquals(2, instr.byteSize(), "CONST_WIDE_16 should be 2 bytes");

			// Round-trip through codec
			Format format = ConstWideInstruction.CODEC.unmap(instr, EMPTY_CONTEXT_UN);
			assertInstanceOf(FormatAAopBBBB.class, format);
			ConstWideInstruction decoded = ConstWideInstruction.CODEC.map(format, EMPTY_CONTEXT);
			assertEquals(instr, decoded, "Codec round-trip should preserve instruction");
		}
	}

	@Test
	void testConstWide32() {
		// 32-bit signed immediate values
		long[] testValues = {Integer.MIN_VALUE, Short.MIN_VALUE - 1, Short.MAX_VALUE + 1, Integer.MAX_VALUE};
		for (long value : testValues) {
			ConstWideInstruction instr = new ConstWideInstruction(2, value);
			assertEquals(ConstWideInstruction.CONST_WIDE_32, instr.opcode(),
					"Should use CONST_WIDE_32 for 32-bit signed values");
			assertEquals(value, instr.value(), "Decoded value should match original");
			assertEquals(4, instr.byteSize(), "CONST_WIDE_32 should be 4 bytes");

			// Round-trip through codec
			Format format = ConstWideInstruction.CODEC.unmap(instr, EMPTY_CONTEXT_UN);
			assertInstanceOf(FormatAAopBBBB32.class, format);
			ConstWideInstruction decoded = ConstWideInstruction.CODEC.map(format, EMPTY_CONTEXT);
			assertEquals(instr, decoded, "Codec round-trip should preserve instruction");
		}
	}

	@Test
	void testConstWideHigh16() {
		// High 16 bits set, low 48 bits zero
		long[] testValues = {0x0001000000000000L, 0x7FFF000000000000L, 0x8000000000000000L, 0xFFFF000000000000L};
		for (long value : testValues) {
			ConstWideInstruction instr = new ConstWideInstruction(3, value);
			assertEquals(ConstWideInstruction.CONST_WIDE_HIGH16, instr.opcode(),
					"Should use CONST_WIDE_HIGH16 for values with only high 16 bits set");
			assertEquals(value, instr.value(), "Decoded value should match original");
			assertEquals(4, instr.byteSize(), "CONST_WIDE_HIGH16 should be 4 bytes");

			// Round-trip through codec
			Format format = ConstWideInstruction.CODEC.unmap(instr, EMPTY_CONTEXT_UN);
			assertInstanceOf(FormatAAopBBBB.class, format);
			ConstWideInstruction decoded = ConstWideInstruction.CODEC.map(format, EMPTY_CONTEXT);
			assertEquals(instr, decoded, "Codec round-trip should preserve instruction");
		}

		// The full 64-bit value should be correctly reconstructed from the high 16 bits
		ConstWideInstruction wideHigh16 = ConstWideInstruction.CODEC.map(
				new FormatAAopBBBB(CONST_WIDE_HIGH16, 0, 0x3ff0), EMPTY_CONTEXT);
		assertEquals(0x3ff0000000000000L, wideHigh16.value());
	}

	@Test
	void testConstWide64() {
		// 64-bit values not covered by other formats
		// - MIN_VALUE is + 1 because Long.MIN_VALUE can be represented as CONST_WIDE_HIGH16
		long[] testValues = {0x123456789ABCDEFL, -0x123456789ABCDEFL, Long.MAX_VALUE , Long.MIN_VALUE + 1 };
		for (long value : testValues) {
			ConstWideInstruction instr = new ConstWideInstruction(4, value);
			assertEquals(ConstWideInstruction.CONST_WIDE, instr.opcode(),
					"Should use CONST_WIDE for general 64-bit values");
			assertEquals(value, instr.value(), "Decoded value should match original");
			assertEquals(8, instr.byteSize(), "CONST_WIDE should be 8 bytes");

			// Round-trip through codec
			Format format = ConstWideInstruction.CODEC.unmap(instr, EMPTY_CONTEXT_UN);
			assertInstanceOf(FormatAAopBBBB64.class, format);
			ConstWideInstruction decoded = ConstWideInstruction.CODEC.map(format, EMPTY_CONTEXT);
			assertEquals(instr, decoded, "Codec round-trip should preserve instruction");
		}
	}

	@Test
	void testInvalidOpcodeByteSize() {
		ConstWideInstruction instr = new ConstWideInstruction(99, 0, 0L);
		assertThrows(IllegalArgumentException.class, instr::byteSize);
	}
}