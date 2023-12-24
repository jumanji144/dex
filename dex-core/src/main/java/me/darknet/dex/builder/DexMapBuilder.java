package me.darknet.dex.builder;

import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapAccess;
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
    private final List<TypeListItem> typeLists = new ArrayList<>();
    private final List<ClassDataItem> classDatas = new ArrayList<>();
    private final List<CodeItem> codes = new ArrayList<>();
    private final List<StringDataItem> stringDatas = new ArrayList<>();
    private final List<DebugInfoItem> debugInfos = new ArrayList<>();

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
        } else if(item instanceof TypeListItem typeListItem) {
            typeLists.add(typeListItem);
        } else if(item instanceof ClassDataItem classDataItem) {
            classDatas.add(classDataItem);
        } else if(item instanceof CodeItem codeItem) {
            codes.add(codeItem);
        } else if(item instanceof StringDataItem stringDataItem) {
            stringDatas.add(stringDataItem);
        } else if(item instanceof DebugInfoItem debugInfoItem) {
            debugInfos.add(debugInfoItem);
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

    public List<TypeListItem> typeLists() {
        return typeLists;
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

    public List<Item> all() {
        return Collections.emptyList();
    }

    @Override
    public DexMap build() {
        return new DexMap(strings, types, protos, fields, methods, classes,
                          typeLists, classDatas, codes, stringDatas, debugInfos);
    }

}
