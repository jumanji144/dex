package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.EncodedMethod;
import me.darknet.dex.file.items.*;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationProcessing;
import me.darknet.dex.tree.definitions.annotation.AnnotationMap;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Collections;
import java.util.ArrayList;

public non-sealed class MethodMember extends Member<MethodType> {

    private Code code;
    private List<String> parameterNames = List.of();
    private List<Integer> parameterAccessFlags = List.of();
    private List<List<Annotation>> parameterAnnotations = List.of();
    private Constant defaultValue;
    private List<String> thrownTypes;

    public MethodMember(@NotNull String name, @NotNull MethodType type, int access) {
        super(type, access, name);
    }

    public @Nullable Code getCode() {
        return code;
    }

    public void setCode(@Nullable Code code) {
        this.code = code;
    }

    public @NotNull List<String> getThrownTypes() {
        return Objects.requireNonNullElse(thrownTypes, Collections.emptyList());
    }

    public void addThrownType(@NotNull String thrownType) {
        if (thrownTypes == null)
            thrownTypes = new ArrayList<>(2);
        thrownTypes.add(thrownType);
    }

    public void setThrownTypes(@Nullable List<String> thrownTypes) {
        this.thrownTypes = thrownTypes;
    }

    public @Nullable List<String> getParameterNames() {
        return parameterNames;
    }

    public void setParameterNames(@Nullable List<String> parameterNames) {
        this.parameterNames = parameterNames == null ? List.of() : parameterNames;
    }

    public @NotNull List<Integer> getParameterAccessFlags() {
        return parameterAccessFlags;
    }

    public void setParameterAccessFlags(@Nullable List<Integer> parameterAccessFlags) {
        this.parameterAccessFlags = parameterAccessFlags == null ? List.of() : parameterAccessFlags;
    }

    /**
     * Parameter annotations come from the annotations directory rather than an annotation set, so unlike
     * {@link #getAnnotations()} they cannot be stored on the member itself.
     *
     * @return Annotations per parameter, in declaration order. A parameter without annotations is represented
     * 		by an empty list, and the list is empty when the method has no parameter annotations at all.
     */
    public @NotNull List<List<Annotation>> getParameterAnnotations() {
        return parameterAnnotations;
    }

    public void setParameterAnnotations(@Nullable List<List<Annotation>> parameterAnnotations) {
        this.parameterAnnotations = parameterAnnotations == null ? List.of() : parameterAnnotations;
    }

    /**
     * @return Value this annotation interface element binds to by default, or {@code null} when the element
     * 		declares no default. The value is carried on the annotation interface's class as a
     * 		{@code dalvik.annotation.AnnotationDefault} annotation and distributed to matching methods on read.
     */
    public @Nullable Constant getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(@Nullable Constant defaultValue) {
        this.defaultValue = defaultValue;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MethodMember that))
            return false;
        if (!super.equals(o))
            return false;

	    return Objects.equals(code, that.code)
                && Objects.equals(parameterNames, that.parameterNames)
                && Objects.equals(parameterAccessFlags, that.parameterAccessFlags)
                && Objects.equals(parameterAnnotations, that.parameterAnnotations)
                && Objects.equals(defaultValue, that.defaultValue)
                && Objects.equals(thrownTypes, that.thrownTypes);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + Objects.hashCode(code);
        result = 31 * result + Objects.hashCode(parameterNames);
        result = 31 * result + Objects.hashCode(parameterAccessFlags);
        result = 31 * result + Objects.hashCode(parameterAnnotations);
        result = 31 * result + Objects.hashCode(defaultValue);
        result = 31 * result + Objects.hashCode(thrownTypes);
        return result;
    }

    public static final MemberCodec<MethodMember, EncodedMethod> CODEC = new MemberCodec<>() {
        @Override
        public MethodMember map(EncodedMethod encoded, AnnotationMap annotations, DexMap context) {
            ProtoItem proto = encoded.method().proto();
            int access = encoded.access();
            String name = encoded.method().name().string();

            MethodType type = Types.methodType(proto);

            MethodMember member = new MethodMember(name, type, access);

            if (encoded.code() != null) {
                Code code = Code.CODEC.map(encoded.code(), context);

                member.setCode(code);
            }

            AnnotationSetItem set = annotations.methodAnnotations().get(encoded.method());
            if (set != null) {
                member.mapAnnotations(set, context);
            }

            List<AnnotationSetItem> parameterSets = annotations.parameterAnnotations().get(encoded.method());
            if (parameterSets != null) {
                member.setParameterAnnotations(mapParameterAnnotations(parameterSets, context));
            }

            return member;
        }

        @Override
        public EncodedMethod unmap(MethodMember member, AnnotationMap annotations, DexMapBuilder context) {
            MethodItem method = context.method(member.getOwner(), member.getName(), member.getType());

            CodeItem code = context.code(member.getCode());

            AnnotationSetItem set = context.annotationSet(AnnotationProcessing.exportMethodAnnotations(member));

            if (set != null)
                annotations.methodAnnotations().put(method, set);

            List<AnnotationSetItem> parameterSets = exportParameterAnnotations(member, context);
            if (parameterSets != null)
                annotations.parameterAnnotations().put(method, parameterSets);

            return new EncodedMethod(method, member.getAccess(), code);
        }
    };

    /**
     * @param sets
     * 		Annotation sets read from the annotations directory, one per parameter, any of which may be
     * 		{@code null} for a parameter that carries no annotations.
     * @param context
     * 		Map the annotations are read from.
     *
     * @return Annotations per parameter, in declaration order.
     */
    private static @NotNull List<List<Annotation>> mapParameterAnnotations(@NotNull List<AnnotationSetItem> sets,
                                                                          @NotNull DexMap context) {
        List<List<Annotation>> parameters = new ArrayList<>(sets.size());
        for (AnnotationSetItem set : sets) {
            // A null entry means the parameter has no annotations, which an empty list expresses just as well.
            if (set == null) {
                parameters.add(List.of());
                continue;
            }
            List<Annotation> parameterAnnotations = new ArrayList<>(set.entries().size());
            for (AnnotationOffItem entry : set.entries()) {
                parameterAnnotations.add(Annotation.CODEC.map(entry.item(), context));
            }
            parameters.add(parameterAnnotations);
        }
        return parameters;
    }

    /**
     * @param member
     * 		Method whose parameter annotations should be written.
     * @param context
     * 		Pools the annotation sets are registered in.
     *
     * @return Annotation sets to place in the annotations directory, or {@code null} when the method has no
     * 		parameter annotations. The returned list is padded to the parameter count, which the format
     * 		requires the reference list to match.
     */
    private static @Nullable List<AnnotationSetItem> exportParameterAnnotations(@NotNull MethodMember member,
                                                                               @NotNull DexMapBuilder context) {
        List<List<Annotation>> parameters = member.getParameterAnnotations();
        if (parameters.isEmpty())
            return null;

        int parameterCount = member.getType().parameterTypes().size();
        int size = Math.max(parameterCount, parameters.size());
        List<AnnotationSetItem> sets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            List<Annotation> parameter = i < parameters.size() ? parameters.get(i) : List.of();
            // Unannotated parameters are written as a null reference, not as an empty set, so the reference
            // list stays aligned with the method descriptor without spending pool entries on empty sets.
            sets.add(parameter.isEmpty() ? null : context.annotationSet(parameter));
        }
        return sets;
    }

}
