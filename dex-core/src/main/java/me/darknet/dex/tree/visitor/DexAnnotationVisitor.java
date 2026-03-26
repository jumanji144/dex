package me.darknet.dex.tree.visitor;

import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.constant.Constant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class DexAnnotationVisitor {
    protected final @Nullable DexAnnotationVisitor delegate;

    public DexAnnotationVisitor() {
        this(null);
    }

    public DexAnnotationVisitor(@Nullable DexAnnotationVisitor delegate) {
        this.delegate = delegate;
    }

    public void visit(@NotNull AnnotationPart annotation) {
        if (delegate != null)
            delegate.visit(annotation);
    }

    public @Nullable DexConstantVisitor visitElement(@NotNull String name, @NotNull Constant value) {
        if (delegate != null)
            return delegate.visitElement(name, value);
        return null;
    }

    public void visitEnd() {
        if (delegate != null)
            delegate.visitEnd();
    }
}
