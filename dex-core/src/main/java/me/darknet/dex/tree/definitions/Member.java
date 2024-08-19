package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.EncodedMember;
import me.darknet.dex.file.items.AnnotationOffItem;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationMap;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Type;

import java.util.ArrayList;
import java.util.List;

public sealed class Member<T extends Type> implements Typed<T>, Accessible, Annotated permits FieldMember, MethodMember {

    private final T type;
    private final int access;
    private final String name;
    private final MemberIdentifier identifier;
    private List<Annotation> annotations = new ArrayList<>();
    private InstanceType owner;

    public Member(T type, int access, String name) {
        this.type = type;
        this.access = access;
        this.name = name;
        this.identifier = new MemberIdentifier(name, type);
    }

    @Override
    public int access() {
        return access;
    }

    @Override
    public T type() {
        return type;
    }

    public InstanceType owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public MemberIdentifier identifier() {
        return identifier;
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public void annotations(List<Annotation> annotations) {
        this.annotations = annotations;
    }

    public void owner(InstanceType owner) {
        this.owner = owner;
    }

    protected void mapAnnotations(AnnotationSetItem set, DexMap map) {
        for (AnnotationOffItem entry : set.entries()) {
            annotations.add(Annotation.CODEC.map(entry.item(), map));
        }
    }

    protected AnnotationSetItem unmapAnnotations(DexMapBuilder builder) {
        return null;
    }

    public interface MemberCodec<M extends Member<?>, C extends EncodedMember> {

        M map(C encoded, AnnotationMap annotations, DexMap context);

        C unmap(M member, AnnotationMap annotations, DexMapBuilder context);

    }

}
