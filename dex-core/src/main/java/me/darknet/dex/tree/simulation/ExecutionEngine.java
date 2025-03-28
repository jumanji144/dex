package me.darknet.dex.tree.simulation;

import me.darknet.dex.tree.definitions.instructions.*;

public interface ExecutionEngine {

    void label(Label label);

    void execute(ArrayInstruction instruction);
    void execute(ArrayLengthInstruction instruction);
    void execute(Binary2AddrInstruction instruction);
    void execute(BinaryInstruction instruction);
    void execute(BinaryLiteralInstruction instruction);
    void execute(BranchInstruction instruction);
    void execute(BranchZeroInstruction instruction);
    void execute(CheckCastInstruction instruction);
    void execute(CompareInstruction instruction);
    void execute(ConstInstruction instruction);
    void execute(ConstTypeInstruction instruction);
    void execute(ConstWideInstruction instruction);
    void execute(ConstStringInstruction instruction);
    void execute(FillArrayDataInstruction instruction);
    void execute(FilledNewArrayInstruction instruction);
    void execute(GotoInstruction instruction);
    void execute(InstanceFieldInstruction instruction);
    void execute(InstanceOfInstruction instruction);
    void execute(InvokeCustomInstruction instruction);
    void execute(InvokeInstruction instruction);
    void execute(MonitorInstruction instruction);
    void execute(MoveExceptionInstruction instruction);
    void execute(MoveInstruction instruction);
    void execute(MoveObjectInstruction instruction);
    void execute(MoveResultInstruction instruction);
    void execute(MoveWideInstruction instruction);
    void execute(NewArrayInstruction instruction);
    void execute(NewInstanceInstruction instruction);
    void execute(NopInstruction instruction);
    void execute(PackedSwitchInstruction instruction);
    void execute(ReturnInstruction instruction);
    void execute(SparseSwitchInstruction instruction);
    void execute(StaticFieldInstruction instruction);
    void execute(ThrowInstruction instruction);
    void execute(UnaryInstruction instruction);

    void execute(Instruction instruction);

    static void execute(ExecutionEngine engine, Instruction instruction) {
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
            default -> throw new IllegalArgumentException("Unknown instruction type: " + instruction);
        }
    }

}
