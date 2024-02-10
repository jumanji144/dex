package me.darknet.dex.file;

import me.darknet.dex.file.items.*;

import java.util.Collections;
import java.util.List;

public record DexMap(List<StringItem> strings, List<TypeItem> types, List<ProtoItem> protos,
                     List<FieldItem> fields, List<MethodItem> methods,
                     List<ClassDefItem> classes, List<CallSiteItem> callSites, List<MethodHandleItem> methodHandles,
                        List<TypeListItem> typeLists, List<AnnotationSetRefList> annotationSetRefLists,
                        List<AnnotationSetItem> annotationSets, List<ClassDataItem> classDatas,
                        List<CodeItem> codes, List<StringDataItem> stringDatas,
                        List<DebugInfoItem> debugInfos, List<AnnotationItem> annotations,
                        List<EncodedArrayItem> encodedArrays, List<AnnotationsDirectoryItem> annotationsDirectories)
        implements DexMapAccess {
    @Override
    public List<Item> all() {
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "DexMap[]";
    }
}
