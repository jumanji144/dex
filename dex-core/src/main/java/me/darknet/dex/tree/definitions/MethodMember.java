package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.EncodedMethod;
import me.darknet.dex.file.annotation.MethodAnnotation;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.file.items.AnnotationsDirectoryItem;
import me.darknet.dex.file.items.Item;
import me.darknet.dex.file.items.ProtoItem;
import me.darknet.dex.tree.definitions.annotation.AnnotationMap;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Types;

import java.util.Map;

public final class MethodMember extends Member<MethodType> {

    private Code code;

    public MethodMember(MethodType type, int access, String name) {
        super(type, access, name);
    }

    public Code code() {
        return code;
    }

    public void code(Code code) {
        this.code = code;
    }

    public static final MemberCodec<MethodMember, EncodedMethod> CODEC = new MemberCodec<>() {
        @Override
        public MethodMember map(EncodedMethod encoded, AnnotationMap annotations, DexMap context) {
            ProtoItem proto = encoded.method().proto();
            int access = encoded.access();
            String name = encoded.method().name().string();

            MethodType type = Types.methodType(proto);

            MethodMember member = new MethodMember(type, access, name);

            Code code = Code.CODEC.map(encoded.code(), context);

            member.code(code);

            AnnotationSetItem set = annotations.methodAnnotations().get(encoded.method());
            if (set != null)
                member.mapAnnotations(set, context);

            return member;
        }

        @Override
        public EncodedMethod unmap(MethodMember member, AnnotationMap annotations, DexMap context) {
            return null;
        }
    };

}
