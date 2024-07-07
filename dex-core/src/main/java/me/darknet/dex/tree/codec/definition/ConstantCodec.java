package me.darknet.dex.tree.codec.definition;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.annotation.EncodedAnnotation;
import me.darknet.dex.file.items.*;
import me.darknet.dex.file.value.*;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.constant.*;
import me.darknet.dex.tree.type.Types;

import java.util.ArrayList;
import java.util.List;

public class ConstantCodec implements TreeCodec<Constant, Value> {

    @Override
    public Constant map(Value input, DexMap context) {
        return switch (input) {
            case AnnotationValue(EncodedAnnotation annotation) ->
                    new AnnotationConstant(AnnotationPart.CODEC.map(annotation, context));
            case ArrayValue(List<Value> values) -> {
                List<Constant> constants = new ArrayList<>(values.size());
                for (Value value : values) {
                    constants.add(map(value, context));
                }
                yield new ArrayConstant(constants);
            }
            case BoolValue(boolean value) -> new BoolConstant(value);
            case ByteValue(byte value) -> new ByteConstant(value);
            case CharValue(char value) -> new CharConstant(value);
            case DoubleValue(double value) -> new DoubleConstant(value);
            case EnumValue(FieldItem field) -> {
                MemberIdentifier identifier = new MemberIdentifier(field.name().string(),
                        Types.classType(field.type()));
                yield new EnumConstant(Types.instanceType(field.owner()), identifier);
            }
            case FloatValue(float value) -> new FloatConstant(value);
            case MethodTypeValue(ProtoItem protoItem) -> new TypeConstant(Types.methodType(protoItem));
            case MethodHandleValue(MethodHandleItem item) -> {
                Handle handle = switch (item.type()) {
                    case Handle.KIND_STATIC_PUT, Handle.KIND_STATIC_GET,
                         Handle.KIND_INSTANCE_PUT, Handle.KIND_INSTANCE_GET -> {
                        FieldItem field = context.fields().get(item.index());
                        yield new Handle(item.type(), Types.instanceType(field.owner()), field.name().string(),
                                Types.classType(field.type()));
                    }
                    case Handle.KIND_INVOKE_STATIC, Handle.KIND_INVOKE_INSTANCE,
                         Handle.KIND_INVOKE_CONSTRUCTOR, Handle.KIND_INVOKE_DIRECT,
                         Handle.KIND_INVOKE_INTERFACE -> {
                        MethodItem method = context.methods().get(item.index());
                        yield new Handle(item.type(), Types.instanceType(method.owner()), method.name().string(),
                                Types.methodType(method.proto()));
                    }
                    default -> throw new IllegalStateException("Unexpected value: " + item.type());
                };
                yield new HandleConstant(handle);
            }
            case StringValue(StringItem string) -> new StringConstant(string.string());
            case TypeValue(TypeItem type) -> new TypeConstant(Types.classType(type));
            case FieldValue(FieldItem field) -> {
                MemberIdentifier identifier = new MemberIdentifier(field.name().string(),
                        Types.classType(field.type()));
                yield new MemberConstant(Types.instanceType(field.owner()), identifier);
            }
            case MethodValue(MethodItem method) -> {
                MemberIdentifier identifier = new MemberIdentifier(method.name().string(),
                        Types.methodType(method.proto()));
                yield new MemberConstant(Types.instanceType(method.owner()), identifier);
            }
            case LongValue(long value) -> new LongConstant(value);
            case IntValue(int value) -> new IntConstant(value);
            case ShortValue(short value) -> new ShortConstant(value);
            case NullValue() -> NullConstant.INSTANCE;
        };
    }

    @Override
    public Value unmap(Constant output, DexMapBuilder context) {
        return null;
    }

}
