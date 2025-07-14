package me.darknet.dex.tree.simulation;

import org.jetbrains.annotations.NotNull;

public interface Simulation<E extends ExecutionEngine, M> {
    void execute(@NotNull E engine, @NotNull M method);
}