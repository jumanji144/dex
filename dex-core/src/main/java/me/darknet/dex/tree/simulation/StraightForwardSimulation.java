package me.darknet.dex.tree.simulation;

import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Label;

public class StraightForwardSimulation implements Simulation<ExecutionEngine, Code> {

    @Override
    public void execute(ExecutionEngine engine, Code code) {
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
