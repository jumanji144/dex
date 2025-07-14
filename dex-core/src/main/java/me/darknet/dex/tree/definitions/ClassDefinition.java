package me.darknet.dex.tree.definitions;

import me.darknet.dex.tree.codec.definition.ClassDefinitionCodec;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.type.InstanceType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClassDefinition implements Typed<InstanceType>, Accessible, Annotated, Signed {

    public static final ClassDefinitionCodec CODEC = new ClassDefinitionCodec();

    private final InstanceType type;
    private final InstanceType superClass;
    private final int access;
    private @Nullable List<InstanceType> interfaces;
    private @Nullable String sourceFile;
    private @Nullable InstanceType enclosingClass;
    private @Nullable MemberIdentifier enclosingMethod;
    private @Nullable List<InnerClass> innerClasses;
    private @Nullable String signature;
    private @Nullable List<InstanceType> memberClasses;
    private @Nullable List<Annotation> annotations;
    private @Nullable Map<MemberIdentifier, FieldMember> fields;
    private @Nullable Map<MemberIdentifier, MethodMember> methods;

    public ClassDefinition(@NotNull InstanceType type, @Nullable InstanceType superClass, int access) {
        this.type = type;
        this.access = access;
        this.superClass = superClass;
    }

    public @NotNull InstanceType getType() {
        return type;
    }

    public int getAccess() {
        return access;
    }

    public @Nullable InstanceType getSuperClass() {
        return superClass;
    }

    public @NotNull List<InstanceType> getInterfaces() {
        if (interfaces == null)
            return Collections.emptyList();
        return interfaces;
    }

    public void addInterface(@NotNull InstanceType itf) {
        if (interfaces == null)
            interfaces = new ArrayList<>();
        interfaces.add(itf);
    }

    public void addInterfaces(@NotNull List<InstanceType> interfaces) {
        interfaces.forEach(this::addInterface);
    }

    public @Nullable String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(@Nullable String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public @Nullable InstanceType getEnclosingClass() {
        return enclosingClass;
    }

    public void setEnclosingClass(@Nullable InstanceType enclosingClass) {
        this.enclosingClass = enclosingClass;
    }

    public @Nullable MemberIdentifier getEnclosingMethod() {
        return enclosingMethod;
    }

    public void setEnclosingMethod(@Nullable MemberIdentifier enclosingMethod) {
        this.enclosingMethod = enclosingMethod;
    }

    public @NotNull List<InnerClass> getInnerClasses() {
        if (innerClasses == null)
            return Collections.emptyList();
        return innerClasses;
    }

    public void addInnerClass(@NotNull InnerClass innerClass) {
        if (innerClasses == null)
            innerClasses = new ArrayList<>();
        innerClasses.add(innerClass);
    }

    public @Nullable String getSignature() {
        return signature;
    }

    public void setSignature(@Nullable String signature) {
        this.signature = signature;
    }

    public @NotNull List<InstanceType> getMemberClasses() {
        if (memberClasses == null)
            return Collections.emptyList();
        return memberClasses;
    }

    public void addMemberClass(@NotNull InstanceType memberClass) {
        if (memberClasses == null)
            memberClasses = new ArrayList<>();
        memberClasses.add(memberClass);
    }

    public @NotNull Map<MemberIdentifier, FieldMember> getFields() {
        if (fields == null)
            return Collections.emptyMap();
        return fields;
    }

    public @NotNull Map<MemberIdentifier, MethodMember> getMethods() {
        if (methods == null)
            return Collections.emptyMap();
        return methods;
    }

    public void putField(@NotNull FieldMember field) {
        field.setOwner(getType());
        if (fields == null)
            fields = new LinkedHashMap<>();
        fields.put(field.getIdentifier(), field);
    }

    public void putMethod(@NotNull MethodMember method) {
        method.setOwner(getType());
        if (methods == null)
            methods = new LinkedHashMap<>();
        methods.put(method.getIdentifier(), method);
    }

    public @Nullable FieldMember getField(@NotNull MemberIdentifier identifier) {
        if (fields == null)
            return null;
        return fields.get(identifier);
    }

    public @Nullable MethodMember getMethod(@NotNull MemberIdentifier identifier) {
        if (methods == null)
            return null;
        return methods.get(identifier);
    }

    public @Nullable FieldMember getField(@NotNull String name, @NotNull String descriptor) {
        if (fields == null)
            return null;
        return fields.get(new MemberIdentifier(name, descriptor));
    }

    public @Nullable MethodMember getMethod(@NotNull String name, @NotNull String descriptor) {
        if (methods == null)
            return null;
        return methods.get(new MemberIdentifier(name, descriptor));
    }

    public @NotNull List<Annotation> getAnnotations() {
        if (annotations == null)
            return Collections.emptyList();
        return annotations;
    }

    public void addAnnotation(@NotNull Annotation annotation) {
        if (annotations == null)
            annotations = new ArrayList<>();
        annotations.add(annotation);
    }

    public void addAnnotations(@NotNull List<Annotation> annotations) {
        annotations.forEach(this::addAnnotation);
    }
}
