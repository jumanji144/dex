package me.darknet.dex.tree.visitor;

import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class DexClassVisitor {
    protected final @Nullable DexClassVisitor delegate;

    public DexClassVisitor() {
        this(null);
    }

    public DexClassVisitor(@Nullable DexClassVisitor delegate) {
        this.delegate = delegate;
    }

    public void visit(@NotNull ClassDefinition definition) {
        if (delegate != null)
            delegate.visit(definition);
    }

    public void visitInnerClass(@NotNull InnerClass innerClass) {
        if (delegate != null)
            delegate.visitInnerClass(innerClass);
    }

    public @Nullable DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
        if (delegate != null)
            return delegate.visitAnnotation(annotation);
        return null;
    }

    public @Nullable DexFieldVisitor visitField(@NotNull FieldMember field) {
        if (delegate != null)
            return delegate.visitField(field);
        return null;
    }

    public @Nullable DexMethodVisitor visitMethod(@NotNull MethodMember method) {
        if (delegate != null)
            return delegate.visitMethod(method);
        return null;
    }

    public void visitEnd() {
        if (delegate != null)
            delegate.visitEnd();
    }
}
