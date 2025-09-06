package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.EncodedMethod;
import me.darknet.dex.file.items.*;
import me.darknet.dex.tree.definitions.annotation.AnnotationMap;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class MethodMember extends Member<MethodType> {

    private Code code;
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
            if (set != null)
                member.mapAnnotations(set, context);

            return member;
        }

        @Override
        public EncodedMethod unmap(MethodMember member, AnnotationMap annotations, DexMapBuilder context) {
            MethodItem method = context.method(member.getOwner(), member.getName(), member.getType());

            CodeItem code = context.code(member.getCode());

            AnnotationSetItem set = context.annotationSet(member.getAnnotations());

            if (set != null)
                annotations.methodAnnotations().put(method, set);

            return new EncodedMethod(method, member.getAccess(), code);
        }
    };

}
