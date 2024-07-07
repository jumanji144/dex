package me.darknet.dex.tree.definitions.constant;

import me.darknet.dex.tree.codec.definition.ConstantCodec;
import me.darknet.dex.tree.type.Type;

public sealed interface Constant permits AnnotationConstant, ArrayConstant, BoolConstant, ByteConstant, CharConstant,
        DoubleConstant, EnumConstant, FloatConstant, HandleConstant, IntConstant, LongConstant, MemberConstant,
        NullConstant, ShortConstant, StringConstant, TypeConstant {

    ConstantCodec CODEC = new ConstantCodec();

    static Constant defaultValue(Type type) {
        return switch (type.descriptor().charAt(0)) {
            case 'Z' -> BoolConstant.FALSE;
            case 'B' -> new ByteConstant((byte) 0);
            case 'C' -> new CharConstant((char) 0);
            case 'S' -> new ShortConstant((short) 0);
            case 'I' -> new IntConstant(0);
            case 'J' -> new LongConstant(0);
            case 'F' -> new FloatConstant(0);
            case 'D' -> new DoubleConstant(0);
            default -> NullConstant.INSTANCE;
        };
    }

}
