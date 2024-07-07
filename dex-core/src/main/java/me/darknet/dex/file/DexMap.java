package me.darknet.dex.file;

import me.darknet.dex.collections.ConstantPool;
import me.darknet.dex.file.items.*;

import java.util.stream.Stream;

public record DexMap(ConstantPool<StringItem> strings, ConstantPool<TypeItem> types, ConstantPool<ProtoItem> protos, ConstantPool<FieldItem> fields,
                     ConstantPool<MethodItem> methods, ConstantPool<ClassDefItem> classes, ConstantPool<CallSiteItem> callSites,
                     ConstantPool<MethodHandleItem> methodHandles, ConstantPool<TypeListItem> typeLists,
                     ConstantPool<AnnotationSetRefList> annotationSetRefLists, ConstantPool<AnnotationSetItem> annotationSets,
                     ConstantPool<ClassDataItem> classDatas, ConstantPool<CodeItem> codes, ConstantPool<StringDataItem> stringDatas,
                     ConstantPool<DebugInfoItem> debugInfos, ConstantPool<AnnotationItem> annotations,
                     ConstantPool<EncodedArrayItem> encodedArrays, ConstantPool<AnnotationsDirectoryItem> annotationsDirectories)
        implements DexMapAccess {
    @Override
    public Stream<Item> all() {
        return Stream.of();
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public String toString() {
        return "DexMap[]";
    }
}
