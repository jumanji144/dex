package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.EncodedField;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.file.items.FieldItem;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.tree.definitions.annotation.AnnotationMap;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;

public final class FieldMember extends Member<ClassType> {

    private Constant staticValue;

    public FieldMember(ClassType type, int access, String name) {
        super(type, access, name);
    }

    public Constant staticValue() {
        return staticValue;
    }

    public void staticValue(Constant value) {
        this.staticValue = value;
    }

    public static final MemberCodec<FieldMember, EncodedField> CODEC = new MemberCodec<>() {
        @Override
        public FieldMember map(EncodedField encoded, AnnotationMap annotations, DexMap context) {
            ClassType type = Types.classType(encoded.field().type());
            int access = encoded.access();
            String name = encoded.field().name().string();

            FieldMember member = new FieldMember(type, access, name);

            AnnotationSetItem set = annotations.fieldAnnotations().get(encoded.field());
            if (set != null)
                member.mapAnnotations(set, context);

            return member;
        }

        @Override
        public EncodedField unmap(FieldMember member, AnnotationMap annotations, DexMapBuilder context) {
            FieldItem field = context.field(member.owner(), member.name(), member.type());

            AnnotationSetItem set = context.annotationSet(member.annotations());

            if (set != null)
                annotations.fieldAnnotations().put(field, set);

            return new EncodedField(field, member.access());
        }
    };

}
