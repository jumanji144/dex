package me.darknet.dex.tree.simulation;

import me.darknet.dex.tree.definitions.instructions.*;
import org.jetbrains.annotations.NotNull;

public interface ExecutionEngine {

    void label(@NotNull Label label);

    void execute(@NotNull ArrayInstruction instruction);
    void execute(@NotNull ArrayLengthInstruction instruction);
    void execute(@NotNull Binary2AddrInstruction instruction);
    void execute(@NotNull BinaryInstruction instruction);
    void execute(@NotNull BinaryLiteralInstruction instruction);
    void execute(@NotNull BranchInstruction instruction);
    void execute(@NotNull BranchZeroInstruction instruction);
    void execute(@NotNull CheckCastInstruction instruction);
    void execute(@NotNull CompareInstruction instruction);
    void execute(@NotNull ConstInstruction instruction);
    void execute(@NotNull ConstTypeInstruction instruction);
    void execute(@NotNull ConstWideInstruction instruction);
    void execute(@NotNull ConstStringInstruction instruction);
    void execute(@NotNull FillArrayDataInstruction instruction);
    void execute(@NotNull FilledNewArrayInstruction instruction);
    void execute(@NotNull GotoInstruction instruction);
    void execute(@NotNull InstanceFieldInstruction instruction);
    void execute(@NotNull InstanceOfInstruction instruction);
    void execute(@NotNull InvokeCustomInstruction instruction);
    void execute(@NotNull InvokeInstruction instruction);
    void execute(@NotNull MonitorInstruction instruction);
    void execute(@NotNull MoveExceptionInstruction instruction);
    void execute(@NotNull MoveInstruction instruction);
    void execute(@NotNull MoveObjectInstruction instruction);
    void execute(@NotNull MoveResultInstruction instruction);
    void execute(@NotNull MoveWideInstruction instruction);
    void execute(@NotNull NewArrayInstruction instruction);
    void execute(@NotNull NewInstanceInstruction instruction);
    void execute(@NotNull NopInstruction instruction);
    void execute(@NotNull PackedSwitchInstruction instruction);
    void execute(@NotNull ReturnInstruction instruction);
    void execute(@NotNull SparseSwitchInstruction instruction);
    void execute(@NotNull StaticFieldInstruction instruction);
    void execute(@NotNull ThrowInstruction instruction);
    void execute(@NotNull UnaryInstruction instruction);

    void execute(@NotNull Instruction instruction);

    static void execute(@NotNull ExecutionEngine engine, @NotNull Instruction instruction) {
        switch (instruction) {
            case ArrayInstruction arrayInstruction -> engine.execute(arrayInstruction);
            case ArrayLengthInstruction arrayLengthInstruction -> engine.execute(arrayLengthInstruction);
            case Binary2AddrInstruction binary2AddrInstruction -> engine.execute(binary2AddrInstruction);
            case BinaryInstruction binaryInstruction -> engine.execute(binaryInstruction);
            case BinaryLiteralInstruction binaryLiteralInstruction -> engine.execute(binaryLiteralInstruction);
            case BranchInstruction branchInstruction -> engine.execute(branchInstruction);
            case BranchZeroInstruction branchZeroInstruction -> engine.execute(branchZeroInstruction);
            case CheckCastInstruction checkCastInstruction -> engine.execute(checkCastInstruction);
            case CompareInstruction compareInstruction -> engine.execute(compareInstruction);
            case ConstInstruction constInstruction -> engine.execute(constInstruction);
            case ConstTypeInstruction constTypeInstruction -> engine.execute(constTypeInstruction);
            case ConstWideInstruction constWideInstruction -> engine.execute(constWideInstruction);
            case ConstStringInstruction constStringInstruction -> engine.execute(constStringInstruction);
            case FillArrayDataInstruction fillArrayDataInstruction -> engine.execute(fillArrayDataInstruction);
            case FilledNewArrayInstruction filledNewArrayInstruction -> engine.execute(filledNewArrayInstruction);
            case GotoInstruction gotoInstruction -> engine.execute(gotoInstruction);
            case InstanceFieldInstruction instanceFieldInstruction -> engine.execute(instanceFieldInstruction);
            case InstanceOfInstruction instanceOfInstruction -> engine.execute(instanceOfInstruction);
            case InvokeCustomInstruction invokeCustomInstruction -> engine.execute(invokeCustomInstruction);
            case InvokeInstruction invokeInstruction -> engine.execute(invokeInstruction);
            case MonitorInstruction monitorInstruction -> engine.execute(monitorInstruction);
            case MoveExceptionInstruction moveExceptionInstruction -> engine.execute(moveExceptionInstruction);
            case MoveInstruction moveInstruction -> engine.execute(moveInstruction);
            case MoveObjectInstruction moveObjectInstruction -> engine.execute(moveObjectInstruction);
            case MoveResultInstruction moveResultInstruction -> engine.execute(moveResultInstruction);
            case MoveWideInstruction moveWideInstruction -> engine.execute(moveWideInstruction);
            case NewArrayInstruction newArrayInstruction -> engine.execute(newArrayInstruction);
            case NewInstanceInstruction newInstanceInstruction -> engine.execute(newInstanceInstruction);
            case NopInstruction nopInstruction -> engine.execute(nopInstruction);
            case PackedSwitchInstruction packedSwitchInstruction -> engine.execute(packedSwitchInstruction);
            case ReturnInstruction returnInstruction -> engine.execute(returnInstruction);
            case SparseSwitchInstruction sparseSwitchInstruction -> engine.execute(sparseSwitchInstruction);
            case StaticFieldInstruction staticFieldInstruction -> engine.execute(staticFieldInstruction);
            case ThrowInstruction throwInstruction -> engine.execute(throwInstruction);
            case UnaryInstruction unaryInstruction -> engine.execute(unaryInstruction);
            default -> throw new IllegalArgumentException("Unknown instruction type: " + instruction.getClass().getName());
        }
    }

}
