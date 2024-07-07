package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.EncodedField;
import me.darknet.dex.file.EncodedMethod;
import me.darknet.dex.file.items.*;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.codec.definition.ClassDefinitionCodec;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClassDefinition implements Typed<InstanceType>, Accessible, Annotated {

    private final InstanceType type;
    private final int access;
    private final InstanceType superClass;
    private List<InstanceType> interfaces = new ArrayList<>();
    private @Nullable String sourceFile;

    private final Map<MemberIdentifier, FieldMember> fields = new HashMap<>(16);
    private final Map<MemberIdentifier, MethodMember> methods = new HashMap<>(16);
    private List<Annotation> annotations = new ArrayList<>();

    public ClassDefinition(InstanceType type, int access, InstanceType superClass) {
        this.type = type;
        this.access = access;
        this.superClass = superClass;
    }

    public InstanceType type() {
        return type;
    }

    public int access() {
        return access;
    }

    public InstanceType superClass() {
        return superClass;
    }

    public List<InstanceType> interfaces() {
        return interfaces;
    }

    public void interfaces(List<InstanceType> interfaces) {
        this.interfaces = interfaces;
    }

    public @Nullable String sourceFile() {
        return sourceFile;
    }

    public void sourceFile(@Nullable String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public Map<MemberIdentifier, FieldMember> fields() {
        return fields;
    }

    public Map<MemberIdentifier, MethodMember> methods() {
        return methods;
    }

    public void putField(FieldMember field) {
        fields.put(field.identifier(), field);
    }

    public void putMethod(MethodMember method) {
        methods.put(method.identifier(), method);
    }

    public @Nullable FieldMember getField(MemberIdentifier identifier) {
        return fields.get(identifier);
    }

    public @Nullable MethodMember getMethod(MemberIdentifier identifier) {
        return methods.get(identifier);
    }

    public @Nullable FieldMember getField(String name, String descriptor) {
        return fields.get(new MemberIdentifier(name, descriptor));
    }

    public @Nullable MethodMember getMethod(String name, String descriptor) {
        return methods.get(new MemberIdentifier(name, descriptor));
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public void annotations(List<Annotation> annotations) {
        this.annotations = annotations;
    }

    public static final ClassDefinitionCodec CODEC = new ClassDefinitionCodec();

}
