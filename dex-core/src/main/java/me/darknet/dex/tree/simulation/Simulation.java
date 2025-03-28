package me.darknet.dex.tree.simulation;

public interface Simulation<E extends ExecutionEngine, M> {
    void execute(E engine, M method);
}