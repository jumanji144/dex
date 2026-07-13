package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatBAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.OpcodeNames;
import me.darknet.dex.tree.type.PrimitiveType;
import org.jetbrains.annotations.NotNull;

public record UnaryInstruction(int opcode, int dest, int source) implements Instruction {

	public UnaryInstruction(int operation, @NotNull PrimitiveType type, int dest, int source) {
		this(UnaryOperation.operation(operation, type), dest, source);
    }

    @Override
    public String toString() {
        return OpcodeNames.name(opcode) + " v" + dest + ", v" + source;
    }

    public static final InstructionCodec<UnaryInstruction, FormatBAop> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull UnaryInstruction map(@NotNull FormatBAop input, @NotNull InstructionContext<DexMap> context) {
            return new UnaryInstruction(input.op(), input.a(), input.b());
        }

		@Override
		public @NotNull FormatBAop unmap(@NotNull UnaryInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
			return new FormatBAop(output.opcode(), output.dest(), output.source());
		}
    };
}
