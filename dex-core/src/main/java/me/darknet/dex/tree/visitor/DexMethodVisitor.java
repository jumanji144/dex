package me.darknet.dex.tree.visitor;

import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.code.Code;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class DexMethodVisitor {
    protected final @Nullable DexMethodVisitor delegate;

    public DexMethodVisitor() {
        this(null);
    }

    public DexMethodVisitor(@Nullable DexMethodVisitor delegate) {
        this.delegate = delegate;
    }

    public void visit(@NotNull MethodMember method) {
        if (delegate != null)
            delegate.visit(method);
    }

    public @Nullable DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
        if (delegate != null)
            return delegate.visitAnnotation(annotation);
        return null;
    }

    public @Nullable DexCodeVisitor visitCode(@NotNull Code code) {
        if (delegate != null)
            return delegate.visitCode(code);
        return null;
    }

    public void visitEnd() {
        if (delegate != null)
            delegate.visitEnd();
    }
}
