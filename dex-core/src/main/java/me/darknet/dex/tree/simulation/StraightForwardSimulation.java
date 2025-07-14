package me.darknet.dex.tree.simulation;

import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import org.jetbrains.annotations.NotNull;

public class StraightForwardSimulation implements Simulation<ExecutionEngine, Code> {

    @Override
    public void execute(@NotNull ExecutionEngine engine, @NotNull Code code) {
        for (Instruction instruction : code.instructions()) {
            if (instruction instanceof Label label) {
                engine.label(label);
            } else {
                ExecutionEngine.execute(engine, instruction);
                engine.execute(instruction);
            }
        }
    }
}
