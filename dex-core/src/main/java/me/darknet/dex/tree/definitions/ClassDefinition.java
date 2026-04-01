package me.darknet.dex.tree.definitions;

import me.darknet.dex.tree.codec.definition.ClassDefinitionCodec;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.visitor.DexClassVisitor;
import me.darknet.dex.tree.visitor.DexTreeWalker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public non-sealed class ClassDefinition implements Typed<InstanceType>, Accessible, Annotated, Signed {

    public static final ClassDefinitionCodec CODEC = new ClassDefinitionCodec();

    private final InstanceType type;
    private @Nullable InstanceType superClass;
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

    public void setSuperClass(@Nullable InstanceType superClass) {
        this.superClass = superClass;
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
        // Sanity check to ensure we only add inner classes that actually belong to this class.
        if (!innerClass.innerClassName().startsWith(innerClass.outerClassName() + "$"))
            return;

        // Initialize if null and add the inner class to the list if its not already present.
        if (innerClasses == null)
            innerClasses = new ArrayList<>();
        for (InnerClass existingInnerClass : innerClasses) {
            if (Objects.equals(existingInnerClass.innerClassName(), innerClass.innerClassName())
                    && Objects.equals(existingInnerClass.innerName(), innerClass.innerName()))
                return;
        }
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
        // TODO: Investigate usage and consider if storing in a List for order would be more appropriate
        //  - Most classes aren't small, so looping to lookup by name+desc is likely not a big deal
        //  - Same for fields below
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

    public void accept(@NotNull DexClassVisitor visitor) {
        DexTreeWalker.accept(this, visitor);
    }

    @Override
    public String toString() {
        return type.internalName();
    }

	@Override
	public final boolean equals(Object o) {
		if (!(o instanceof ClassDefinition that))
			return false;

		return access == that.access
				&& type.equals(that.type)
				&& Objects.equals(superClass, that.superClass)
				&& Objects.equals(interfaces, that.interfaces)
				&& Objects.equals(sourceFile, that.sourceFile)
				&& Objects.equals(enclosingClass, that.enclosingClass)
				&& Objects.equals(enclosingMethod, that.enclosingMethod)
				&& Objects.equals(innerClasses, that.innerClasses)
				&& Objects.equals(signature, that.signature)
				&& Objects.equals(memberClasses, that.memberClasses)
				&& Objects.equals(annotations, that.annotations)
				&& Objects.equals(fields, that.fields)
				&& Objects.equals(methods, that.methods);
	}

	@Override
	public int hashCode() {
		int result = type.hashCode();
		result = 31 * result + Objects.hashCode(superClass);
        result = 31 * result + access;
        result = 31 * result + Objects.hashCode(interfaces);
        result = 31 * result + Objects.hashCode(sourceFile);
        result = 31 * result + Objects.hashCode(enclosingClass);
        result = 31 * result + Objects.hashCode(enclosingMethod);
        result = 31 * result + Objects.hashCode(innerClasses);
        result = 31 * result + Objects.hashCode(signature);
        result = 31 * result + Objects.hashCode(memberClasses);
        result = 31 * result + Objects.hashCode(annotations);
        result = 31 * result + Objects.hashCode(fields);
        result = 31 * result + Objects.hashCode(methods);
        return result;
    }
}
