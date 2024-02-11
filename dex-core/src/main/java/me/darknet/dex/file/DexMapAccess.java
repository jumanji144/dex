package me.darknet.dex.file;

import me.darknet.dex.file.items.*;

import java.util.List;

public interface DexMapAccess {

    List<Item> all();

    List<StringItem> strings();

    List<TypeItem> types();

    List<ProtoItem> protos();

    List<FieldItem> fields();

    List<MethodItem> methods();

    List<ClassDefItem> classes();

    List<CallSiteItem> callSites();

    List<MethodHandleItem> methodHandles();

    List<TypeListItem> typeLists();

    List<AnnotationSetRefList> annotationSetRefLists();

    List<AnnotationSetItem> annotationSets();

    List<ClassDataItem> classDatas();

    List<CodeItem> codes();

    List<StringDataItem> stringDatas();

    List<DebugInfoItem> debugInfos();

    List<AnnotationItem> annotations();

    List<EncodedArrayItem> encodedArrays();

    List<AnnotationsDirectoryItem> annotationsDirectories();

    int size();

}
