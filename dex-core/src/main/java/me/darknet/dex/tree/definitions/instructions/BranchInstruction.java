package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatBAopCCCC;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.OpcodeNames;
import org.jetbrains.annotations.NotNull;

public record BranchInstruction(int test, int a, int b, Label label) implements Instruction {

	// Convenience constants for the test field, rather than having to compute the offset from IF_EQ every time.
	// Mostly useful for writing tests.
	public static int TEST_IF_EQ = 0;
	public static int TEST_IF_NE = IF_NE - IF_EQ;
	public static int TEST_IF_LT = IF_LT - IF_EQ;
	public static int TEST_IF_GE = IF_GE - IF_EQ;
	public static int TEST_IF_GT = IF_GT - IF_EQ;
	public static int TEST_IF_LE = IF_LE - IF_EQ;
	public static int TEST_IF_EQZ = IF_EQZ - IF_EQ;
	public static int TEST_IF_NEZ = IF_NEZ - IF_EQ;
	public static int TEST_IF_LTZ = IF_LTZ - IF_EQ;
	public static int TEST_IF_GEZ = IF_GEZ - IF_EQ;
	public static int TEST_IF_GTZ = IF_GTZ - IF_EQ;
	public static int TEST_IF_LEZ = IF_LEZ - IF_EQ;

	@Override
	public int opcode() {
		return Opcodes.IF_EQ + test;
	}

	@Override
	public String toString() {
		return OpcodeNames.name(opcode()) + " v" + a + ", v" + b + ", " + label;
	}

	public static final InstructionCodec<BranchInstruction, FormatBAopCCCC> CODEC = new InstructionCodec<>() {
		@Override
		public @NotNull BranchInstruction map(@NotNull FormatBAopCCCC input, @NotNull InstructionContext<DexMap> context) {
			return new BranchInstruction(input.op() - Opcodes.IF_EQ, input.a(), input.b(),
					context.label(input, (short) input.c()));
		}

		@Override
		public @NotNull FormatBAopCCCC unmap(@NotNull BranchInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
			return new FormatBAopCCCC(output.opcode(), output.a(), output.b(),
					(short) context.labelOffset(output, output.label));
		}
	};

	@Override
	public int byteSize() {
		return 2;
	}
}
