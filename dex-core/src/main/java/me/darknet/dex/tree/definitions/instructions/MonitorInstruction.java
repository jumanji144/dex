package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.FormatAAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.jetbrains.annotations.NotNull;

public record MonitorInstruction(int register, boolean exit) implements Instruction {

    @Override
    public int opcode() {
        return exit ? MONITOR_EXIT : MONITOR_ENTER;
    }

    @Override
    public String toString() {
        return (exit ? "monitor-exit v" : "monitor-enter v") + register;
    }

    public static final InstructionCodec<MonitorInstruction, FormatAAop> CODEC = new InstructionCodec<>() {
        @Override
        public @NotNull MonitorInstruction map(@NotNull FormatAAop input, @NotNull InstructionContext<DexMap> context) {
            return new MonitorInstruction(input.a(), input.op() == MONITOR_EXIT);
        }

        @Override
        public @NotNull FormatAAop unmap(@NotNull MonitorInstruction output, @NotNull InstructionContext<DexMapBuilder> context) {
            return new FormatAAop(output.opcode(), output.register());
        }
    };

}
