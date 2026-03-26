package me.darknet.dex.tree.codec.definition;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAopBBBB;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static me.darknet.dex.file.instructions.Opcodes.CONST_16;
import static me.darknet.dex.file.instructions.Opcodes.IF_EQZ;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InstructionContext}
 */
class InstructionContextTest {
	@Test
	void labelWithInvalidOffsetThrows() {
		// If there are no instructions, any offset is invalid
		InstructionContext<DexMapBuilder> ctx = contextWithOffsets(List.of(), List.of(100, 23, 50));
		assertThrows(IllegalArgumentException.class, () -> ctx.label(123));
		assertThrows(IllegalArgumentException.class, () -> ctx.labelInexact(10));
	}

	@Test
	void labelInexactReturnsClosestLower() {
		InstructionContext<DexMapBuilder> ctx = contextWithOffsets(
				List.of(new Object(), new Object(), new Object()),
				List.of(10, 20, 30)
		);

		// The 'indexact' value of '25' lands between the instructions at offsets 20 and 30.
		// Since that is the 2nd instruction (index 1), the label should point to that instruction, not the next one.
		Label label = ctx.labelInexact(25);
		assertEquals(1, label.index());
		assertEquals(20, label.position());
	}

	@Test
	void labelWithFormatAndInvalidOffsetThrows() {
		// The instruction is present in the context, but the target offset is not present.
		FormatAAopBBBB instr = new FormatAAopBBBB(0, 0, 0);
		InstructionContext<DexMapBuilder> ctx = contextWithOffsets(
				List.of(instr),
				List.of(0)
		);
		assertThrows(IllegalArgumentException.class, () -> ctx.label(instr, 100));
	}

	@Test
	void repeatedLabelCallsReturnSameLabelObject() {
		// Repeated calls to a label for the same offset should return the same Label object.
		Object instr = new Object();
		InstructionContext<DexMapBuilder> ctx = contextWithOffsets(List.of(instr), List.of(10));
		Label l1 = ctx.label(10);
		Label l2 = ctx.label(10);
		assertSame(l1, l2);

		// Same for inexact labels at the same offset
		l1 = ctx.labelInexact(15);
		l2 = ctx.labelInexact(15);
		assertSame(l1, l2);

		// Same for inexact labels that land on the same instruction
		l1 = ctx.labelInexact(40);
		l2 = ctx.labelInexact(41);
		assertSame(l1, l2);
		assertEquals(l1.index(), l2.index());
	}

	@Test
	void resolvesRepeatedEqualFormatsByIdentity() {
		FormatAAopBBBB first = new FormatAAopBBBB(IF_EQZ, 0, 2);
		FormatAAopBBBB filler = new FormatAAopBBBB(CONST_16, 0, 0);
		FormatAAopBBBB second = new FormatAAopBBBB(IF_EQZ, 0, 2);
		FormatAAopBBBB target = new FormatAAopBBBB(CONST_16, 1, 0);
		InstructionContext<DexMap> ctx = contextWithOffsets(
				List.of(first, filler, second, target),
				List.of(10, 12, 20, 22)
		);

		// The second instruction is identical to the first,
		// but should be treated as a separate instruction at a different offset
		Label label = ctx.label(second, 2);
		assertEquals(22, label.position());
	}

	@Test
	void computesOffsetsForRepeatedEqualInstructionsByIdentity() {
		Label target = new Label(3, 22);
		GotoInstruction first = new GotoInstruction(target);
		GotoInstruction filler = new GotoInstruction(new Label(0, 12));
		GotoInstruction second = new GotoInstruction(target);
		InstructionContext<DexMapBuilder> ctx = contextWithOffsets(
				List.of(first, filler, second, target),
				List.of(10, 12, 20, 22)
		);

		// Relative offset from the second instruction to the target should be 2 (22 - 20)
		int offset = ctx.labelOffset(second, target);
		assertEquals(2, offset);
	}

	// Helper to create a minimal context
	@SuppressWarnings({"unchecked", "rawtypes"})
	private <T extends DexMapAccess> InstructionContext<T> contextWithOffsets(List<?> instructions,
	                                                                          List<Integer> offsets) {
		return new InstructionContext(
				instructions,
				offsets,
				new DexMapBuilder(),
				new HashMap<>(),
				null, null, null
		);
	}
}