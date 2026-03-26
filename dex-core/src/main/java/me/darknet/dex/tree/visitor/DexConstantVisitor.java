package me.darknet.dex.tree.visitor;

import me.darknet.dex.tree.definitions.constant.AnnotationConstant;
import me.darknet.dex.tree.definitions.constant.ArrayConstant;
import me.darknet.dex.tree.definitions.constant.BoolConstant;
import me.darknet.dex.tree.definitions.constant.ByteConstant;
import me.darknet.dex.tree.definitions.constant.CharConstant;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.DoubleConstant;
import me.darknet.dex.tree.definitions.constant.EnumConstant;
import me.darknet.dex.tree.definitions.constant.FloatConstant;
import me.darknet.dex.tree.definitions.constant.HandleConstant;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.LongConstant;
import me.darknet.dex.tree.definitions.constant.MemberConstant;
import me.darknet.dex.tree.definitions.constant.NullConstant;
import me.darknet.dex.tree.definitions.constant.ShortConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.definitions.constant.TypeConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class DexConstantVisitor {
    protected final @Nullable DexConstantVisitor delegate;

    public DexConstantVisitor() {
        this(null);
    }

    public DexConstantVisitor(@Nullable DexConstantVisitor delegate) {
        this.delegate = delegate;
    }

    public void visitConstant(@NotNull Constant constant) {
        if (delegate != null)
            delegate.visitConstant(constant);
    }

    public @Nullable DexAnnotationVisitor visitAnnotationConstant(@NotNull AnnotationConstant constant) {
        if (delegate != null)
            return delegate.visitAnnotationConstant(constant);
        return null;
    }

    public @Nullable DexConstantVisitor visitArrayConstant(@NotNull ArrayConstant constant) {
        if (delegate != null)
            return delegate.visitArrayConstant(constant);
        return null;
    }

    public void visitBoolConstant(@NotNull BoolConstant constant) {
        if (delegate != null)
            delegate.visitBoolConstant(constant);
    }

    public void visitByteConstant(@NotNull ByteConstant constant) {
        if (delegate != null)
            delegate.visitByteConstant(constant);
    }

    public void visitCharConstant(@NotNull CharConstant constant) {
        if (delegate != null)
            delegate.visitCharConstant(constant);
    }

    public void visitDoubleConstant(@NotNull DoubleConstant constant) {
        if (delegate != null)
            delegate.visitDoubleConstant(constant);
    }

    public void visitEnumConstant(@NotNull EnumConstant constant) {
        if (delegate != null)
            delegate.visitEnumConstant(constant);
    }

    public void visitFloatConstant(@NotNull FloatConstant constant) {
        if (delegate != null)
            delegate.visitFloatConstant(constant);
    }

    public void visitHandleConstant(@NotNull HandleConstant constant) {
        if (delegate != null)
            delegate.visitHandleConstant(constant);
    }

    public void visitIntConstant(@NotNull IntConstant constant) {
        if (delegate != null)
            delegate.visitIntConstant(constant);
    }

    public void visitLongConstant(@NotNull LongConstant constant) {
        if (delegate != null)
            delegate.visitLongConstant(constant);
    }

    public void visitMemberConstant(@NotNull MemberConstant constant) {
        if (delegate != null)
            delegate.visitMemberConstant(constant);
    }

    public void visitNullConstant(@NotNull NullConstant constant) {
        if (delegate != null)
            delegate.visitNullConstant(constant);
    }

    public void visitShortConstant(@NotNull ShortConstant constant) {
        if (delegate != null)
            delegate.visitShortConstant(constant);
    }

    public void visitStringConstant(@NotNull StringConstant constant) {
        if (delegate != null)
            delegate.visitStringConstant(constant);
    }

    public void visitTypeConstant(@NotNull TypeConstant constant) {
        if (delegate != null)
            delegate.visitTypeConstant(constant);
    }

    public void visitEnd() {
        if (delegate != null)
            delegate.visitEnd();
    }
}
