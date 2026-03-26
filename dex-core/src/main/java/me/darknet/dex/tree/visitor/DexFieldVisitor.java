package me.darknet.dex.tree.visitor;

import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.constant.Constant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class DexFieldVisitor {
    protected final @Nullable DexFieldVisitor delegate;

    public DexFieldVisitor() {
        this(null);
    }

    public DexFieldVisitor(@Nullable DexFieldVisitor delegate) {
        this.delegate = delegate;
    }

    public void visit(@NotNull FieldMember field) {
        if (delegate != null)
            delegate.visit(field);
    }

    public @Nullable DexConstantVisitor visitStaticValue(@NotNull Constant value) {
        if (delegate != null)
            return delegate.visitStaticValue(value);
        return null;
    }

    public @Nullable DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
        if (delegate != null)
            return delegate.visitAnnotation(annotation);
        return null;
    }

    public void visitEnd() {
        if (delegate != null)
            delegate.visitEnd();
    }
}
