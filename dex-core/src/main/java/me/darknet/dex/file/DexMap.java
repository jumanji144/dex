package me.darknet.dex.file;

import me.darknet.dex.collections.ConstantPool;
import me.darknet.dex.file.items.*;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public record DexMap(ConstantPool<StringItem> strings, ConstantPool<TypeItem> types, ConstantPool<ProtoItem> protos, ConstantPool<FieldItem> fields,
                     ConstantPool<MethodItem> methods, ConstantPool<ClassDefItem> classes, ConstantPool<CallSiteItem> callSites,
                     ConstantPool<MethodHandleItem> methodHandles, ConstantPool<TypeListItem> typeLists,
                     ConstantPool<AnnotationSetRefList> annotationSetRefLists, ConstantPool<AnnotationSetItem> annotationSets,
                     ConstantPool<ClassDataItem> classDatas, ConstantPool<CodeItem> codes, ConstantPool<StringDataItem> stringDatas,
                     ConstantPool<DebugInfoItem> debugInfos, ConstantPool<AnnotationItem> annotations,
                     ConstantPool<EncodedArrayItem> encodedArrays, ConstantPool<AnnotationsDirectoryItem> annotationsDirectories,
                     int size)
        implements DexMapAccess {
    @Override
    public Stream<Item> all() {
        return Stream.of();
    }

    @Override
    public String toString() {
        return "DexMap[]";
    }
}
