package me.darknet.dex.file;

import me.darknet.dex.collections.ConstantPool;
import me.darknet.dex.file.items.*;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Stream;

public interface DexMapAccess {

    @NotNull Stream<Item> all();

    @NotNull ConstantPool<StringItem> strings();

    @NotNull ConstantPool<TypeItem> types();

    @NotNull ConstantPool<ProtoItem> protos();

    @NotNull ConstantPool<FieldItem> fields();

    @NotNull ConstantPool<MethodItem> methods();

    @NotNull ConstantPool<ClassDefItem> classes();

    @NotNull ConstantPool<CallSiteItem> callSites();

    @NotNull ConstantPool<MethodHandleItem> methodHandles();

    @NotNull ConstantPool<TypeListItem> typeLists();

    @NotNull ConstantPool<AnnotationSetRefList> annotationSetRefLists();

    @NotNull ConstantPool<AnnotationSetItem> annotationSets();

    @NotNull ConstantPool<ClassDataItem> classDatas();

    @NotNull ConstantPool<CodeItem> codes();

    @NotNull ConstantPool<StringDataItem> stringDatas();

    @NotNull ConstantPool<DebugInfoItem> debugInfos();

    @NotNull ConstantPool<AnnotationItem> annotations();

    @NotNull ConstantPool<EncodedArrayItem> encodedArrays();

    @NotNull ConstantPool<AnnotationsDirectoryItem> annotationsDirectories();

    int size();

}
