package me.darknet.dex.file;

import me.darknet.dex.builder.Builder;
import me.darknet.dex.collections.ConstantPool;
import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.file.items.*;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Type;

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

    public DexMapBuilder add(Item item) {
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

    public ConstantPool<StringItem> strings() {
        return strings;
    }

    public ConstantPool<TypeItem> types() {
        return types;
    }

    public ConstantPool<ProtoItem> protos() {
        return protos;
    }

    public ConstantPool<FieldItem> fields() {
        return fields;
    }

    public ConstantPool<MethodItem> methods() {
        return methods;
    }

    public ConstantPool<ClassDefItem> classes() {
        return classes;
    }

    public ConstantPool<CallSiteItem> callSites() {
        return callSites;
    }

    public ConstantPool<MethodHandleItem> methodHandles() {
        return methodHandles;
    }

    public ConstantPool<TypeListItem> typeLists() {
        return typeLists;
    }

    public ConstantPool<AnnotationSetRefList> annotationSetRefLists() {
        return annotationSetRefLists;
    }

    public ConstantPool<AnnotationSetItem> annotationSets() {
        return annotationSets;
    }

    public ConstantPool<ClassDataItem> classDatas() {
        return classDatas;
    }

    public ConstantPool<CodeItem> codes() {
        return codes;
    }

    public ConstantPool<StringDataItem> stringDatas() {
        return stringDatas;
    }

    public ConstantPool<DebugInfoItem> debugInfos() {
        return debugInfos;
    }

    public ConstantPool<AnnotationItem> annotations() {
        return annotations;
    }

    public ConstantPool<EncodedArrayItem> encodedArrays() {
        return encodedArrays;
    }

    public ConstantPool<AnnotationsDirectoryItem> annotationsDirectories() {
        return annotationsDirectories;
    }

    public Stream<Item> all() {
        return Stream.of();
    }

    public int size() {
        return 0;
    }

    // tree helpers

    public TypeItem type(Type value) {
        TypeItem typeItem = new TypeItem(string(value.descriptor()));
        types.add(typeItem);
        return typeItem;
    }

    public int addType(Type value) {
        return types.add(type(value));
    }

    public TypeListItem typeList(List<? extends Type> types) {
        List<TypeItem> items = new ArrayList<>(types.size());
        for (Type type : types) {
            items.add(type(type));
        }
        TypeListItem item = new TypeListItem(items);
        typeLists.add(item);
        return item;
    }

    public StringItem string(String value) {
        StringDataItem data = new StringDataItem(value);
        StringItem item = new StringItem(data);
        stringDatas.add(data);
        strings.add(item);
        return item;
    }

    public int addString(String value) {
        StringDataItem data = new StringDataItem(value);
        StringItem item = new StringItem(data);
        stringDatas.add(data);
        return strings.add(item);
    }

    public ProtoItem proto(MethodType type) {
        TypeItem returnType = type(type.returnType());
        TypeListItem parameters = typeList(type.parameterTypes());
        StringItem shorty = string(type.descriptor());
        ProtoItem proto = new ProtoItem(shorty, returnType, parameters);
        protos.add(proto);
        return proto;
    }

    public int addProto(MethodType type) {
        return protos.add(proto(type));
    }

    public MethodItem method(InstanceType owner, String name, MethodType type) {
        TypeItem ownerType = type(owner);
        ProtoItem proto = proto(type);
        StringItem nameItem = string(name);
        MethodItem method = new MethodItem(ownerType, proto, nameItem);
        methods.add(method);
        return method;
    }

    public int addMethod(InstanceType owner, String name, MethodType type) {
        return methods.add(method(owner, name, type));
    }

    public FieldItem field(InstanceType owner, String name, Type type) {
        TypeItem ownerType = type(owner);
        TypeItem fieldType = type(type);
        StringItem nameItem = string(name);
        FieldItem field = new FieldItem(ownerType, fieldType, nameItem);
        fields.add(field);
        return field;
    }

    public int addField(InstanceType owner, String name, Type type) {
        return fields.add(field(owner, name, type));
    }

    @Override
    public DexMap build() {
        return new DexMap(strings, types, protos, fields, methods, classes, callSites, methodHandles, typeLists,
                annotationSetRefLists, annotationSets, classDatas, codes, stringDatas, debugInfos, annotations,
                encodedArrays, annotationsDirectories);
    }

}
