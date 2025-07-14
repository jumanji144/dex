package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.EncodedMember;
import me.darknet.dex.file.items.AnnotationOffItem;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationMap;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public sealed class Member<T extends Type> implements Typed<T>, Accessible, Annotated, Signed permits FieldMember, MethodMember {

    private final T type;
    private final int access;
    private final String name;
    private final MemberIdentifier identifier;
    private @Nullable List<Annotation> annotations;
    private @Nullable String signature;
    private @Nullable InstanceType owner;

    public Member(T type, int access, String name) {
        this.type = type;
        this.access = access;
        this.name = name;
        this.identifier = new MemberIdentifier(name, type);
    }

    @Override
    public int getAccess() {
        return access;
    }

    @Override
    public @NotNull T getType() {
        return type;
    }

    public @Nullable InstanceType getOwner() {
        return owner;
    }

    public @NotNull String getName() {
        return name;
    }

    public @NotNull MemberIdentifier getIdentifier() {
        return identifier;
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

    public void setOwner(@Nullable InstanceType owner) {
        this.owner = owner;
    }

    @Override
    public @Nullable String getSignature() {
        return signature;
    }

    @Override
    public void setSignature(@Nullable String signature) {
        this.signature = signature;
    }

    protected void mapAnnotations(@NotNull AnnotationSetItem set, @NotNull DexMap map) {
        for (AnnotationOffItem entry : set.entries()) {
            Annotation annotation = Annotation.CODEC.map(entry.item(), map);

            if (annotation.visibility() == Annotation.VISIBILITY_SYSTEM) {
                var anno = annotation.annotation();

                // TODO: Recycle code in ClassDefinitionCodec
                //  - this doesn't support all signatures, but the CDC does

                if (anno.type().internalName().equals("dalvik/annotation/Signature")) {
                    var element = anno.element("value");
                    if (element instanceof StringConstant(String value)) {
                        setSignature(value);
                    }
                }
            }

            addAnnotation(annotation);
        }
    }

    protected void unmapAnnotations(@NotNull DexMapBuilder builder) {
        // TODO: this is never used, and this should also recycle code in ClassDefinitionCodec
        if (signature != null) {
            // check if we have a signature annotation
            builder.type("dalvik/annotation/Signature");
            AnnotationPart part = new AnnotationPart(Types.instanceTypeFromInternalName("dalvik/annotation/Signature"),
                    Map.of("value", new StringConstant(signature)));
            Annotation signatureAnnotation = new Annotation((byte) Annotation.VISIBILITY_SYSTEM, part);

            addAnnotation(signatureAnnotation);
        }
    }

    public interface MemberCodec<M extends Member<?>, C extends EncodedMember> {

        M map(C encoded, AnnotationMap annotations, DexMap context);

        C unmap(M member, AnnotationMap annotations, DexMapBuilder context);

    }

}
