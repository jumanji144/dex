package me.darknet.dex.file;

import me.darknet.dex.builder.Builder;
import me.darknet.dex.collections.ConstantPool;
import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.file.items.*;
import me.darknet.dex.file.value.MethodHandleValue;
import me.darknet.dex.file.value.MethodTypeValue;
import me.darknet.dex.file.value.StringValue;
import me.darknet.dex.file.value.Value;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.Handle;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class DexMapBuilder implements Builder<DexMap>, DexMapAccess {

    private final ConstantPool<StringItem> strings = new ConstantPool<>();
    private final ConstantPool<TypeItem> types = new ConstantPool<>();
    private final ConstantPool<ProtoItem> protos = new ConstantPool<>();
    private final ConstantPool<FieldItem> fields = new ConstantPool<>();
    private final ConstantPool<MethodItem> methods = new ConstantPool<>();
    private final ConstantPool<ClassDefItem> classes = new ConstantPool<>();
    private final ConstantPool<CallSiteItem> callSites = new ConstantPool<>();
    private final ConstantPool<MethodHandleItem> methodHandles = new ConstantPool<>();
    private final ConstantPool<TypeListItem> typeLists = new ConstantPool<>();
    private final ConstantPool<AnnotationSetRefList> annotationSetRefLists = new ConstantPool<>();
    private final ConstantPool<AnnotationSetItem> annotationSets = new ConstantPool<>();
    private final ConstantPool<ClassDataItem> classDatas = new ConstantPool<>();
    private final ConstantPool<CodeItem> codes = new ConstantPool<>();
    private final ConstantPool<StringDataItem> stringDatas = new ConstantPool<>();
    private final ConstantPool<DebugInfoItem> debugInfos = new ConstantPool<>();
    private final ConstantPool<AnnotationItem> annotations = new ConstantPool<>();
    private final ConstantPool<EncodedArrayItem> encodedArrays = new ConstantPool<>();
    private final ConstantPool<AnnotationsDirectoryItem> annotationsDirectories = new ConstantPool<>();

    public @NotNull DexMapBuilder add(Item item) {
        switch (item) {
            case StringItem stringItem -> strings.add(stringItem);
            case TypeItem typeItem -> types.add(typeItem);
            case ProtoItem protoItem -> protos.add(protoItem);
            case FieldItem fieldItem -> fields.add(fieldItem);
            case MethodItem methodItem -> methods.add(methodItem);
            case ClassDefItem classDefItem -> classes.add(classDefItem);
            case CallSiteItem callSiteItem -> callSites.add(callSiteItem);
            case MethodHandleItem mhItem -> methodHandles.add(mhItem);
            case TypeListItem typeListItem -> typeLists.add(typeListItem);
            case AnnotationSetRefList annotationSetRefList -> annotationSetRefLists.add(annotationSetRefList);
            case AnnotationSetItem annotationSetItem -> annotationSets.add(annotationSetItem);
            case ClassDataItem classDataItem -> classDatas.add(classDataItem);
            case CodeItem codeItem -> codes.add(codeItem);
            case StringDataItem stringDataItem -> stringDatas.add(stringDataItem);
            case DebugInfoItem debugInfoItem -> debugInfos.add(debugInfoItem);
            case AnnotationItem annotationItem -> annotations.add(annotationItem);
            case EncodedArrayItem encodedArrayItem -> encodedArrays.add(encodedArrayItem);
            case AnnotationsDirectoryItem annotationsDirectoryItem -> annotationsDirectories.add(annotationsDirectoryItem);
            case null, default -> {
            }
        }
        return this;
    }

    public @NotNull ConstantPool<StringItem> strings() {
        return strings;
    }

    public @NotNull ConstantPool<TypeItem> types() {
        return types;
    }

    public @NotNull ConstantPool<ProtoItem> protos() {
        return protos;
    }

    public @NotNull ConstantPool<FieldItem> fields() {
        return fields;
    }

    public @NotNull ConstantPool<MethodItem> methods() {
        return methods;
    }

    public @NotNull ConstantPool<ClassDefItem> classes() {
        return classes;
    }

    public @NotNull ConstantPool<CallSiteItem> callSites() {
        return callSites;
    }

    public @NotNull ConstantPool<MethodHandleItem> methodHandles() {
        return methodHandles;
    }

    public @NotNull ConstantPool<TypeListItem> typeLists() {
        return typeLists;
    }

    public @NotNull ConstantPool<AnnotationSetRefList> annotationSetRefLists() {
        return annotationSetRefLists;
    }

    public @NotNull ConstantPool<AnnotationSetItem> annotationSets() {
        return annotationSets;
    }

    public @NotNull ConstantPool<ClassDataItem> classDatas() {
        return classDatas;
    }

    public @NotNull ConstantPool<CodeItem> codes() {
        return codes;
    }

    public @NotNull ConstantPool<StringDataItem> stringDatas() {
        return stringDatas;
    }

    public @NotNull ConstantPool<DebugInfoItem> debugInfos() {
        return debugInfos;
    }

    public @NotNull ConstantPool<AnnotationItem> annotations() {
        return annotations;
    }

    public @NotNull ConstantPool<EncodedArrayItem> encodedArrays() {
        return encodedArrays;
    }

    public @NotNull ConstantPool<AnnotationsDirectoryItem> annotationsDirectories() {
        return annotationsDirectories;
    }

    public @NotNull Stream<Item> all() {
        return Stream.of();
    }

    public int size() {
        return 0;
    }

    // tree helpers

    public @NotNull TypeItem type(@NotNull Type value) {
        TypeItem typeItem = new TypeItem(string(value.descriptor()));
        types.add(typeItem);
        return typeItem;
    }

    public @NotNull TypeItem type(@NotNull String descriptor) {
        TypeItem typeItem = new TypeItem(string(descriptor));
        types.add(typeItem);
        return typeItem;
    }

    public int addType(@NotNull Type value) {
        return types.add(type(value));
    }

    public @NotNull TypeListItem typeList(@Nullable List<? extends Type> types) {
        if (types == null || types.isEmpty()) {
            return TypeListItem.EMPTY;
        }
        List<TypeItem> items = new ArrayList<>(types.size());
        for (Type type : types) {
            items.add(type(type));
        }
        TypeListItem item = new TypeListItem(items);
        typeLists.add(item);
        return item;
    }

    public @NotNull StringItem string(@NotNull String value) {
        StringDataItem data = new StringDataItem(value);
        StringItem item = new StringItem(data);
        stringDatas.add(data);
        strings.add(item);
        return item;
    }

    public int addString(@NotNull String value) {
        StringDataItem data = new StringDataItem(value);
        StringItem item = new StringItem(data);
        stringDatas.add(data);
        return strings.add(item);
    }

    public @NotNull ProtoItem proto(@NotNull MethodType type) {
        TypeItem returnType = type(type.returnType());
        TypeListItem parameters = typeList(type.parameterTypes());
        StringItem shorty = string(Types.shortyDescriptor(type));
        ProtoItem proto = new ProtoItem(shorty, returnType, parameters);
        protos.add(proto);
        return proto;
    }

    public int addProto(@NotNull MethodType type) {
        return protos.add(proto(type));
    }

    public @NotNull MethodItem method(@NotNull InstanceType owner, @NotNull String name, @NotNull MethodType type) {
        TypeItem ownerType = type(owner);
        ProtoItem proto = proto(type);
        StringItem nameItem = string(name);
        MethodItem method = new MethodItem(ownerType, proto, nameItem);
        methods.add(method);
        return method;
    }

    public int addMethod(@NotNull InstanceType owner, @NotNull String name, @NotNull MethodType type) {
        return methods.add(method(owner, name, type));
    }

    public FieldItem field(@NotNull InstanceType owner, @NotNull String name, @NotNull Type type) {
        TypeItem ownerType = type(owner);
        TypeItem fieldType = type(type);
        StringItem nameItem = string(name);
        FieldItem field = new FieldItem(ownerType, fieldType, nameItem);
        fields.add(field);
        return field;
    }

    public int addField(@NotNull InstanceType owner, @NotNull String name, @NotNull Type type) {
        return fields.add(field(owner, name, type));
    }

    public @NotNull MethodHandleItem methodHandle(@NotNull Handle handle) {
        MethodHandleItem item = Handle.CODEC.unmap(handle, this);
        methodHandles.add(item);
        return item;
    }

    public @NotNull CallSiteItem callSite(@NotNull Handle handle, @NotNull String name, @NotNull MethodType type, @NotNull List<Constant> constants) {
        MethodHandleItem methodHandle = methodHandle(handle);
        StringItem nameItem = string(name);
        ProtoItem proto = proto(type);

        List<Value> values = new ArrayList<>(constants.size());
        for (Constant constant : constants) {
            values.add(Constant.CODEC.unmap(constant, this));
        }

        CallSiteDataItem data = new CallSiteDataItem(
                new MethodHandleValue(methodHandle), new StringValue(nameItem), new MethodTypeValue(proto),
                values);

        CallSiteItem item = new CallSiteItem(data);
        callSites.add(item);

        return item;
    }

    public @NotNull AnnotationItem annotation(@NotNull Annotation annotation) {
        AnnotationItem item = Annotation.CODEC.unmap(annotation, this);
        annotations.add(item);
        return item;
    }

    public @Nullable AnnotationSetItem annotationSet(@Nullable List<Annotation> annotations) {
        if (annotations == null || annotations.isEmpty())
            return null;
        List<AnnotationOffItem> items = new ArrayList<>(annotations.size());
        for (Annotation annotation : annotations) {
            AnnotationItem item = annotation(annotation);
            items.add(new AnnotationOffItem(item));
        }
        AnnotationSetItem item = new AnnotationSetItem(items);
        annotationSets.add(item);
        return item;
    }

    public @Nullable CodeItem code(@Nullable Code code) {
        if (code == null)
            return null;
        CodeItem item = Code.CODEC.unmap(code, this);
        codes.add(item);
        return item;
    }

    public int addCallSite(@NotNull Handle handle, @NotNull String name, @NotNull MethodType type, @NotNull List<Constant> constants) {
        return callSites.add(callSite(handle, name, type, constants));
    }

    @Override
    public @NotNull DexMap build() {
        return new DexMap(strings, types, protos, fields, methods, classes, callSites, methodHandles, typeLists,
                annotationSetRefLists, annotationSets, classDatas, codes, stringDatas, debugInfos, annotations,
                encodedArrays, annotationsDirectories);
    }

}
