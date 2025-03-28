package me.darknet.dex.tree.simulation;

public class SimulationException extends RuntimeException {
    public SimulationException(String message) {
        super(message);
    }

    public SimulationException(String message, Throwable cause) {
        super(message, cause);
    }

    public SimulationException(Throwable cause) {
        super(cause);
    }
}
