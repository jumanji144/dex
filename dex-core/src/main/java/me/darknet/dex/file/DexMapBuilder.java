package me.darknet.dex.file;

import me.darknet.dex.builder.Builder;
import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.file.items.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DexMapBuilder implements Builder<DexMap>, DexMapAccess {

    private final List<StringItem> strings = new ArrayList<>();
    private final List<TypeItem> types = new ArrayList<>();
    private final List<ProtoItem> protos = new ArrayList<>();
    private final List<FieldItem> fields = new ArrayList<>();
    private final List<MethodItem> methods = new ArrayList<>();
    private final List<ClassDefItem> classes = new ArrayList<>();
    private final List<CallSiteItem> callSites = new ArrayList<>();
    private final List<MethodHandleItem> methodHandles = new ArrayList<>();
    private final List<TypeListItem> typeLists = new ArrayList<>();
    private final List<AnnotationSetRefList> annotationSetRefLists = new ArrayList<>();
    private final List<AnnotationSetItem> annotationSets = new ArrayList<>();
    private final List<ClassDataItem> classDatas = new ArrayList<>();
    private final List<CodeItem> codes = new ArrayList<>();
    private final List<StringDataItem> stringDatas = new ArrayList<>();
    private final List<DebugInfoItem> debugInfos = new ArrayList<>();
    private final List<AnnotationItem> annotations = new ArrayList<>();
    private final List<EncodedArrayItem> encodedArrays = new ArrayList<>();
    private final List<AnnotationsDirectoryItem> annotationsDirectories = new ArrayList<>();

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
        return this;
    }

    public List<StringItem> strings() {
        return strings;
    }

    public List<TypeItem> types() {
        return types;
    }

    public List<ProtoItem> protos() {
        return protos;
    }

    public List<FieldItem> fields() {
        return fields;
    }

    public List<MethodItem> methods() {
        return methods;
    }

    public List<ClassDefItem> classes() {
        return classes;
    }

    public List<CallSiteItem> callSites() {
        return callSites;
    }

    public List<MethodHandleItem> methodHandles() {
        return methodHandles;
    }

    public List<TypeListItem> typeLists() {
        return typeLists;
    }

    public List<AnnotationSetRefList> annotationSetRefLists() {
        return annotationSetRefLists;
    }

    public List<AnnotationSetItem> annotationSets() {
        return annotationSets;
    }

    public List<ClassDataItem> classDatas() {
        return classDatas;
    }

    public List<CodeItem> codes() {
        return codes;
    }

    public List<StringDataItem> stringDatas() {
        return stringDatas;
    }

    public List<DebugInfoItem> debugInfos() {
        return debugInfos;
    }

    public List<AnnotationItem> annotations() {
        return annotations;
    }

    public List<EncodedArrayItem> encodedArrays() {
        return encodedArrays;
    }

    public List<AnnotationsDirectoryItem> annotationsDirectories() {
        return annotationsDirectories;
    }

    public List<Item> all() {
        return Collections.emptyList();
    }

    @Override
    public DexMap build() {
        return new DexMap(strings, types, protos, fields, methods, classes, callSites, methodHandles, typeLists,
                annotationSetRefLists, annotationSets, classDatas, codes, stringDatas, debugInfos, annotations,
                encodedArrays, annotationsDirectories);
    }

}
