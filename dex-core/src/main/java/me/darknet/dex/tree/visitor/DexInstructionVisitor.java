package me.darknet.dex.tree.visitor;

import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.ArrayLengthInstruction;
import me.darknet.dex.tree.definitions.instructions.Binary2AddrInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
import me.darknet.dex.tree.definitions.instructions.CompareInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstMethodHandleInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstMethodTypeInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstStringInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstTypeInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstWideInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.MonitorInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveObjectInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveResultInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveWideInstruction;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.NopInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.SparseSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import me.darknet.dex.tree.definitions.instructions.UnaryInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class DexInstructionVisitor {
    protected final @Nullable DexInstructionVisitor delegate;

    public DexInstructionVisitor() {
        this(null);
    }

    public DexInstructionVisitor(@Nullable DexInstructionVisitor delegate) {
        this.delegate = delegate;
    }

    public void visitInstruction(@NotNull Instruction instruction) {
        if (delegate != null)
            delegate.visitInstruction(instruction);
    }

    public void visitArrayInstruction(@NotNull ArrayInstruction instruction) {
        if (delegate != null)
            delegate.visitArrayInstruction(instruction);
    }

    public void visitArrayLengthInstruction(@NotNull ArrayLengthInstruction instruction) {
        if (delegate != null)
            delegate.visitArrayLengthInstruction(instruction);
    }

    public void visitBinary2AddrInstruction(@NotNull Binary2AddrInstruction instruction) {
        if (delegate != null)
            delegate.visitBinary2AddrInstruction(instruction);
    }

    public void visitBinaryInstruction(@NotNull BinaryInstruction instruction) {
        if (delegate != null)
            delegate.visitBinaryInstruction(instruction);
    }

    public void visitBinaryLiteralInstruction(@NotNull BinaryLiteralInstruction instruction) {
        if (delegate != null)
            delegate.visitBinaryLiteralInstruction(instruction);
    }

    public void visitBranchInstruction(@NotNull BranchInstruction instruction) {
        if (delegate != null)
            delegate.visitBranchInstruction(instruction);
    }

    public void visitBranchZeroInstruction(@NotNull BranchZeroInstruction instruction) {
        if (delegate != null)
            delegate.visitBranchZeroInstruction(instruction);
    }

    public void visitCheckCastInstruction(@NotNull CheckCastInstruction instruction) {
        if (delegate != null)
            delegate.visitCheckCastInstruction(instruction);
    }

    public void visitCompareInstruction(@NotNull CompareInstruction instruction) {
        if (delegate != null)
            delegate.visitCompareInstruction(instruction);
    }

    public void visitConstInstruction(@NotNull ConstInstruction instruction) {
        if (delegate != null)
            delegate.visitConstInstruction(instruction);
    }

    public void visitConstMethodHandleInstruction(@NotNull ConstMethodHandleInstruction instruction) {
        if (delegate != null)
            delegate.visitConstMethodHandleInstruction(instruction);
    }

    public void visitConstMethodTypeInstruction(@NotNull ConstMethodTypeInstruction instruction) {
        if (delegate != null)
            delegate.visitConstMethodTypeInstruction(instruction);
    }

    public void visitConstStringInstruction(@NotNull ConstStringInstruction instruction) {
        if (delegate != null)
            delegate.visitConstStringInstruction(instruction);
    }

    public void visitConstTypeInstruction(@NotNull ConstTypeInstruction instruction) {
        if (delegate != null)
            delegate.visitConstTypeInstruction(instruction);
    }

    public void visitConstWideInstruction(@NotNull ConstWideInstruction instruction) {
        if (delegate != null)
            delegate.visitConstWideInstruction(instruction);
    }

    public void visitFillArrayDataInstruction(@NotNull FillArrayDataInstruction instruction) {
        if (delegate != null)
            delegate.visitFillArrayDataInstruction(instruction);
    }

    public void visitFilledNewArrayInstruction(@NotNull FilledNewArrayInstruction instruction) {
        if (delegate != null)
            delegate.visitFilledNewArrayInstruction(instruction);
    }

    public void visitGotoInstruction(@NotNull GotoInstruction instruction) {
        if (delegate != null)
            delegate.visitGotoInstruction(instruction);
    }

    public void visitInstanceFieldInstruction(@NotNull InstanceFieldInstruction instruction) {
        if (delegate != null)
            delegate.visitInstanceFieldInstruction(instruction);
    }

    public void visitInstanceOfInstruction(@NotNull InstanceOfInstruction instruction) {
        if (delegate != null)
            delegate.visitInstanceOfInstruction(instruction);
    }

    public void visitInvokeCustomInstruction(@NotNull InvokeCustomInstruction instruction) {
        if (delegate != null)
            delegate.visitInvokeCustomInstruction(instruction);
    }

    public void visitInvokeInstruction(@NotNull InvokeInstruction instruction) {
        if (delegate != null)
            delegate.visitInvokeInstruction(instruction);
    }

    public void visitLabel(@NotNull Label instruction) {
        if (delegate != null)
            delegate.visitLabel(instruction);
    }

    public void visitMonitorInstruction(@NotNull MonitorInstruction instruction) {
        if (delegate != null)
            delegate.visitMonitorInstruction(instruction);
    }

    public void visitMoveExceptionInstruction(@NotNull MoveExceptionInstruction instruction) {
        if (delegate != null)
            delegate.visitMoveExceptionInstruction(instruction);
    }

    public void visitMoveInstruction(@NotNull MoveInstruction instruction) {
        if (delegate != null)
            delegate.visitMoveInstruction(instruction);
    }

    public void visitMoveObjectInstruction(@NotNull MoveObjectInstruction instruction) {
        if (delegate != null)
            delegate.visitMoveObjectInstruction(instruction);
    }

    public void visitMoveResultInstruction(@NotNull MoveResultInstruction instruction) {
        if (delegate != null)
            delegate.visitMoveResultInstruction(instruction);
    }

    public void visitMoveWideInstruction(@NotNull MoveWideInstruction instruction) {
        if (delegate != null)
            delegate.visitMoveWideInstruction(instruction);
    }

    public void visitNewArrayInstruction(@NotNull NewArrayInstruction instruction) {
        if (delegate != null)
            delegate.visitNewArrayInstruction(instruction);
    }

    public void visitNewInstanceInstruction(@NotNull NewInstanceInstruction instruction) {
        if (delegate != null)
            delegate.visitNewInstanceInstruction(instruction);
    }

    public void visitNopInstruction(@NotNull NopInstruction instruction) {
        if (delegate != null)
            delegate.visitNopInstruction(instruction);
    }

    public void visitPackedSwitchInstruction(@NotNull PackedSwitchInstruction instruction) {
        if (delegate != null)
            delegate.visitPackedSwitchInstruction(instruction);
    }

    public void visitReturnInstruction(@NotNull ReturnInstruction instruction) {
        if (delegate != null)
            delegate.visitReturnInstruction(instruction);
    }

    public void visitSparseSwitchInstruction(@NotNull SparseSwitchInstruction instruction) {
        if (delegate != null)
            delegate.visitSparseSwitchInstruction(instruction);
    }

    public void visitStaticFieldInstruction(@NotNull StaticFieldInstruction instruction) {
        if (delegate != null)
            delegate.visitStaticFieldInstruction(instruction);
    }

    public void visitThrowInstruction(@NotNull ThrowInstruction instruction) {
        if (delegate != null)
            delegate.visitThrowInstruction(instruction);
    }

    public void visitUnaryInstruction(@NotNull UnaryInstruction instruction) {
        if (delegate != null)
            delegate.visitUnaryInstruction(instruction);
    }
}
