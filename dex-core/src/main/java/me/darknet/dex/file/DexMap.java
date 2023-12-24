package me.darknet.dex.file;

import me.darknet.dex.file.items.*;

import java.util.Collections;
import java.util.List;

public record DexMap(List<StringItem> strings, List<TypeItem> types, List<ProtoItem> protos,
                     List<FieldItem> fields, List<MethodItem> methods, List<ClassDefItem> classes,
                     List<TypeListItem> typeLists, List<ClassDataItem> classDatas, List<CodeItem> codes,
                     List<StringDataItem> stringDatas, List<DebugInfoItem> debugInfos)
        implements DexMapAccess {
    @Override
    public List<Item> all() {
        return Collections.emptyList();
    }
}
