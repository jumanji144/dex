package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.EncodedMember;
import me.darknet.dex.file.items.AnnotationItem;
import me.darknet.dex.file.items.AnnotationOffItem;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationMap;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public sealed class Member<T extends Type> implements Typed<T>, Accessible, Annotated, Signed permits FieldMember, MethodMember {

    private final T type;
    private final int access;
    private final String name;
    private final MemberIdentifier identifier;
    private List<Annotation> annotations = new ArrayList<>();
    private @Nullable String signature;
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
            Annotation annotation = Annotation.CODEC.map(entry.item(), map);

            if (annotation.visibility() == Annotation.VISIBILITY_SYSTEM) {
                var anno = annotation.annotation();
                if (anno.type().internalName().equals("dalvik/annotation/Signature")) {
                    var element = anno.element("value");
                    if (element instanceof StringConstant(String value)) {
                        signature(value);
                    }
                }
            }

            annotations.add(annotation);
        }
    }

    protected void unmapAnnotations(DexMapBuilder builder) {
        if (signature != null) {
            // check if we have a signature annotation
            builder.type("dalvik/annotation/Signature");
            AnnotationPart part = new AnnotationPart(Types.instanceTypeFromInternalName("dalvik/annotation/Signature"),
                    Map.of("value", new StringConstant(signature)));
            Annotation signatureAnnotation = new Annotation((byte) Annotation.VISIBILITY_SYSTEM, part);

            annotations.add(signatureAnnotation);
        }
    }

    @Override
    public @Nullable String signature() {
        return signature;
    }

    public void signature(@Nullable String signature) {
        this.signature = signature;
    }

    public interface MemberCodec<M extends Member<?>, C extends EncodedMember> {

        M map(C encoded, AnnotationMap annotations, DexMap context);

        C unmap(M member, AnnotationMap annotations, DexMapBuilder context);

    }

}
