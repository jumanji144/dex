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
import me.darknet.dex.tree.type.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ConstantCodec implements TreeCodec<Constant, Value> {

    @Override
    public @NotNull Constant map(@NotNull Value input, @NotNull DexMap context) {
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
                Handle handle = Handle.CODEC.map(item, context);
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
    public @NotNull Value unmap(@NotNull Constant output, @NotNull DexMapBuilder context) {
        return switch (output) {
            case AnnotationConstant(AnnotationPart annotation) ->
                new AnnotationValue(AnnotationPart.CODEC.unmap(annotation, context));
            case ArrayConstant(List<Constant> constants) -> {
                List<Value> values = new ArrayList<>(constants.size());
                for (Constant constant : constants) {
                    values.add(unmap(constant, context));
                }
                yield new ArrayValue(values);
            }
            case BoolConstant value -> new BoolValue(value.value());
            case ByteConstant value -> new ByteValue(value.value());
            case CharConstant value -> new CharValue(value.value());
            case DoubleConstant value -> new DoubleValue(value.value());
            case EnumConstant(InstanceType owner, MemberIdentifier identifier) -> {
                FieldItem item = new FieldItem(context.type(owner), context.type(identifier.descriptor()),
                        context.string(identifier.name()));
                context.add(item);
                yield new EnumValue(item);
            }
            case FloatConstant value -> new FloatValue(value.value());
            case HandleConstant(Handle handle) -> {
                MethodHandleItem item = Handle.CODEC.unmap(handle, context);
                context.add(item);
                yield new MethodHandleValue(item);
            }
            case StringConstant value -> {
                StringItem item = context.string(value.value());
                yield new StringValue(item);
            }
            case TypeConstant(InstanceType type) -> {
                TypeItem item = context.type(type);
                yield new TypeValue(item);
            }
            case MemberConstant(InstanceType owner, MemberIdentifier identifier) -> {
                Type type = TypeParser.parse(identifier.descriptor());
                if (type instanceof MethodType mt) {
                    MethodItem item = context.method(owner, identifier.name(), mt);
                    context.add(item);
                    yield new MethodValue(item);
                } else {
                    FieldItem item = context.field(owner, identifier.name(), type);
                    context.add(item);
                    yield new FieldValue(item);
                }
            }
            case LongConstant value -> new LongValue(value.value());
            case IntConstant value -> new IntValue(value.value());
            case ShortConstant value -> new ShortValue(value.value());
            case NullConstant ignored -> NullValue.INSTANCE;
            default -> throw new IllegalStateException("Unexpected value: " + output);
        };
    }

}
