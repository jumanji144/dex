package me.darknet.dex.file;

import me.darknet.dex.builder.Builder;
import me.darknet.dex.collections.ConstantPool;
import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.file.items.*;

import java.util.ArrayList;
import java.util.Collections;
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
    private int size;

    public DexMapBuilder add(Item item) {
        if(item instanceof StringItem stringItem) {
            strings.add(stringItem);
        } else if(item instanceof TypeItem typeItem) {
            types.add(typeItem);
        } else if(item instanceof ProtoItem protoItem) {
            protos.add(protoItem);
        } else if(item instanceof FieldItem fieldItem) {
            fields.add(fieldItem);
        } else if(item instanceof MethodItem methodItem) {
            methods.add(methodItem);
        } else if(item instanceof ClassDefItem classDefItem) {
            classes.add(classDefItem);
        } else if (item instanceof CallSiteItem callSiteItem) {
            callSites.add(callSiteItem);
        } else if (item instanceof MethodHandleItem mhItem) {
            methodHandles.add(mhItem);
        } else if(item instanceof TypeListItem typeListItem) {
            typeLists.add(typeListItem);
        } else if(item instanceof AnnotationSetRefList annotationSetRefList) {
            annotationSetRefLists.add(annotationSetRefList);
        } else if(item instanceof AnnotationSetItem annotationSetItem) {
            annotationSets.add(annotationSetItem);
        } else if(item instanceof ClassDataItem classDataItem) {
            classDatas.add(classDataItem);
        } else if(item instanceof CodeItem codeItem) {
            codes.add(codeItem);
        } else if(item instanceof StringDataItem stringDataItem) {
            stringDatas.add(stringDataItem);
        } else if(item instanceof DebugInfoItem debugInfoItem) {
            debugInfos.add(debugInfoItem);
        } else if(item instanceof AnnotationItem annotationItem) {
            annotations.add(annotationItem);
        } else if(item instanceof EncodedArrayItem encodedArrayItem) {
            encodedArrays.add(encodedArrayItem);
        } else if(item instanceof AnnotationsDirectoryItem annotationsDirectoryItem) {
            annotationsDirectories.add(annotationsDirectoryItem);
        }
        size++;
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
        return size;
    }

    @Override
    public DexMap build() {
        return new DexMap(strings, types, protos, fields, methods, classes, callSites, methodHandles, typeLists,
                annotationSetRefLists, annotationSets, classDatas, codes, stringDatas, debugInfos, annotations,
                encodedArrays, annotationsDirectories, size);
    }

}
