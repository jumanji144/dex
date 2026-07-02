package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.EncodedMethod;
import me.darknet.dex.file.items.*;
import me.darknet.dex.tree.definitions.annotation.AnnotationProcessing;
import me.darknet.dex.tree.definitions.annotation.AnnotationMap;
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

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MethodMember that))
            return false;
        if (!super.equals(o))
            return false;

	    return Objects.equals(code, that.code)
                && Objects.equals(parameterNames, that.parameterNames)
                && Objects.equals(parameterAccessFlags, that.parameterAccessFlags)
                && Objects.equals(thrownTypes, that.thrownTypes);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + Objects.hashCode(code);
        result = 31 * result + Objects.hashCode(parameterNames);
        result = 31 * result + Objects.hashCode(parameterAccessFlags);
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

            return member;
        }

        @Override
        public EncodedMethod unmap(MethodMember member, AnnotationMap annotations, DexMapBuilder context) {
            MethodItem method = context.method(member.getOwner(), member.getName(), member.getType());

            CodeItem code = context.code(member.getCode());

            AnnotationSetItem set = context.annotationSet(AnnotationProcessing.exportMethodAnnotations(member));

            if (set != null)
                annotations.methodAnnotations().put(method, set);

            return new EncodedMethod(method, member.getAccess(), code);
        }
    };

}
