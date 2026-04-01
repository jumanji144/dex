package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.EncodedMember;
import me.darknet.dex.file.items.AnnotationOffItem;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.tree.definitions.annotation.AnnotationProcessing;
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
import java.util.Objects;

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

            // Process metadata annotations, which are "system" annotations used to store metadata like:
            // - signatures,
            // - enclosing class/method info
            // - etc.
            // These are not meant to be exposed as "real" annotations.
            // Drop these when found (the call will fill in the metadata they represent in our model / 'this').
            if (annotation.visibility() == Annotation.VISIBILITY_SYSTEM
                    && AnnotationProcessing.processAttribute(Collections.emptyMap(), this, annotation.annotation()))
               continue;

            addAnnotation(annotation);
        }
    }

    protected void unmapAnnotations(@NotNull DexMapBuilder builder) {
        // TODO: this is never used which will break round-trip tests with annotations used to convey metadata.
        //  - This should also delegate to 'AnnotationProcessing' for consistency / reuse
        //    which should also house unmapping support later.
        if (signature != null) {

            // check if we have a signature annotation
            builder.type("dalvik/annotation/Signature");
            AnnotationPart part = new AnnotationPart(Types.instanceTypeFromInternalName("dalvik/annotation/Signature"),
                    Map.of("value", new StringConstant(signature)));
            Annotation signatureAnnotation = new Annotation((byte) Annotation.VISIBILITY_SYSTEM, part);

            addAnnotation(signatureAnnotation);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Member<?> member))
            return false;

        return access == member.access
                && type.equals(member.type)
                && name.equals(member.name)
                && identifier.equals(member.identifier)
                && Objects.equals(annotations, member.annotations)
                && Objects.equals(signature, member.signature)
                && Objects.equals(owner, member.owner);
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + access;
        result = 31 * result + name.hashCode();
        result = 31 * result + identifier.hashCode();
        result = 31 * result + Objects.hashCode(annotations);
        result = 31 * result + Objects.hashCode(signature);
        result = 31 * result + Objects.hashCode(owner);
        return result;
    }

    @Override
    public String toString() {
        return getType() + " " + getName();
    }

    public interface MemberCodec<M extends Member<?>, C extends EncodedMember> {

        M map(C encoded, AnnotationMap annotations, DexMap context);

        C unmap(M member, AnnotationMap annotations, DexMapBuilder context);

    }

}
