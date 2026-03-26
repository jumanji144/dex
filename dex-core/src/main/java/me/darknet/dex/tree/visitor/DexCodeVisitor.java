package me.darknet.dex.tree.visitor;

import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class DexCodeVisitor extends DexInstructionVisitor {
    protected final @Nullable DexCodeVisitor delegate;

    public DexCodeVisitor() {
        this(null);
    }

    public DexCodeVisitor(@Nullable DexCodeVisitor delegate) {
        super(delegate);
        this.delegate = delegate;
    }

    public void visit(@NotNull Code code) {
        if (delegate != null)
            delegate.visit(code);
    }

    public void visitTryCatch(@NotNull TryCatch tryCatch) {
        if (delegate != null)
            delegate.visitTryCatch(tryCatch);
    }

    public void visitTryCatchHandler(@NotNull TryCatch tryCatch, @NotNull Handler handler) {
        if (delegate != null)
            delegate.visitTryCatchHandler(tryCatch, handler);
    }

    public @Nullable DexConstantVisitor visitBootstrapArgument(@NotNull InvokeCustomInstruction instruction,
                                                               int index,
                                                               @NotNull Constant argument) {
        if (delegate != null)
            return delegate.visitBootstrapArgument(instruction, index, argument);
        return null;
    }

    public void visitDebugInfo(@NotNull DebugInformation debugInformation) {
        if (delegate != null)
            delegate.visitDebugInfo(debugInformation);
    }

    public void visitLineNumber(@NotNull DebugInformation.LineNumber lineNumber) {
        if (delegate != null)
            delegate.visitLineNumber(lineNumber);
    }

    public void visitLocalVariable(@NotNull DebugInformation.LocalVariable localVariable) {
        if (delegate != null)
            delegate.visitLocalVariable(localVariable);
    }

    public void visitEnd() {
        if (delegate != null)
            delegate.visitEnd();
    }
}
