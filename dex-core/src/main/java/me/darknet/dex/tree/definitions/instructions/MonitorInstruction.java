package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.FormatAAop;

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
        public MonitorInstruction map(FormatAAop input) {
            return new MonitorInstruction(input.a(), input.op() == MONITOR_EXIT);
        }

        @Override
        public FormatAAop unmap(MonitorInstruction output) {
            return new FormatAAop(output.opcode(), output.register());
        }
    };

}
