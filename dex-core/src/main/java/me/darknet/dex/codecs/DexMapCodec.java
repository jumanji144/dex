package me.darknet.dex.codecs;

import me.darknet.dex.collections.ConstantPool;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.HiddenApiData;
import me.darknet.dex.file.items.*;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.io.Sections;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DexMapCodec implements Codec<DexMap>, ItemTypes {

    private volatile WriteState writeState;
    private static final ItemCodec<?>[] CODECS;

    /**
     * Pools that items address by index, in the order their sections have to be read in. Every other
     * section keeps its file order.
     */
    private static final List<Integer> POOL_SECTIONS = List.of(
            TYPE_STRING_ID_ITEM, TYPE_TYPE_ID_ITEM, TYPE_PROTO_ID_ITEM, TYPE_FIELD_ID_ITEM, TYPE_METHOD_ID_ITEM,
            TYPE_METHOD_HANDLE_ITEM, TYPE_CALL_SITE_ID_ITEM);

    /** Sorts map entries so pools are decoded first; stable, so the file order of the rest survives. */
    private static final Comparator<MapEntry> READ_ORDER = Comparator.comparingInt(entry -> readRank(entry.type));

    public Sections sections() {
        return state().sections();
    }

    public Map<Object, Integer> offsets() {
        return state().offsets();
    }

    public int dataOffset() {
        return state().dataOffset();
    }

    @Override
    public @NotNull DexMap read(@NotNull Input input) throws IOException {
        return ItemCodec.withFreshCache(() -> {
            DexMapBuilder builder = new DexMapBuilder();
            long size = input.readUnsignedInt();
            List<MapEntry> entries = new ArrayList<>((int) size);
            for (long i = 0; i < size; i++) {
                int type = input.readUnsignedShort();
                input.readUnsignedShort(); // unused
                long amount = input.readUnsignedInt();
                int offset = input.readInt();
                entries.add(new MapEntry(type, amount, offset));
            }

            // Read the pools before the sections that index into them. File order alone is not enough: the
            // map list stores call_site_id_item (0x0007) ahead of method_handle_item (0x0008), while every
            // call site indexes the method handle pool, so the handles have to be materialised first.
            // Reading order is otherwise irrelevant, as items are read from their own slices and every
            // reference into another section is resolved by slicing its offset when it is read.
            entries.sort(READ_ORDER);

            for (int i = 0; i < entries.size(); i++) {
                MapEntry entry = entries.get(i);
                if (entry.type == TYPE_HEADER_ITEM || entry.type == TYPE_MAP_LIST) {
                    continue;
                }
                if (entry.type == TYPE_HIDDENAPI_CLASS_DATA_ITEM) {
                    int nextOffset = input.size();
                    for (MapEntry candidate : entries) {
                        if (candidate.offset > entry.offset && candidate.offset < nextOffset) {
                            nextOffset = candidate.offset;
                        }
                    }
                    int length = Math.max(0, nextOffset - entry.offset);
                    builder.hiddenApi(new HiddenApiData((int) entry.amount, input.slice(entry.offset, length).readBytes(length)));
                    continue;
                }

                Input slice = input.slice(entry.offset);
                for (long j = 0; j < entry.amount; j++) {
                    Item item = CODECS[index(entry.type)].read(slice, builder);
                    builder.add(item);
                }
            }
            return builder.build();
        });
    }

    private <T extends Item> void write(T item, @NotNull ItemCodec<T> codec, @NotNull Output output, @NotNull WriteContext context) throws IOException {
        // align to alignment
        int position = output.position();
        position = (position + codec.alignment() - 1) & -codec.alignment();
        output.position(position);
        context.offsets().put(item, output.position());
        codec.write(item, output, context);
    }

    private void writeMapEntry(@NotNull Output output, int type, int size, int offset) throws IOException {
        if(size == 0) return;
        output.writeShort(type);
        output.writeShort(0);
        output.writeInt(size);
        output.writeInt(offset);
    }

    private <T> void writeMapEntry(@NotNull Output output, int type, @NotNull ConstantPool<T> objects,
                                   @NotNull WriteContext context) throws IOException {
        if(!objects.isEmpty()) {
            writeMapEntry(output, type, objects.size(), context.offsets().get(objects.get(0)) + context.dataOffset());
        }
    }

    private int putOffset(@NotNull Output section, @NotNull Map<Object, Integer> offsets, int offset) {
        offsets.put(section, offset);
        return offset + section.position();
    }

    @Override
    public void write(@NotNull DexMap value, @NotNull Output output) throws IOException {
        Sections sections = new Sections(output);
        Map<Object, Integer> offsets = new HashMap<>();
        WriteContext context = createContext(value, offsets);

        writeBasic(value, sections, context);
        writeAnnotations(value, sections, context);
        writeClasses(value, sections, context);

        // compute offsets for sections
        computeOffsets(sections, context);

        writeMap(value, sections, context);
        writeState = new WriteState(sections, Map.copyOf(offsets), context.dataOffset());
    }

    private int computeNumberItems(@NotNull DexMap map) {
        int size = 2; // header item and map list

        if(!map.strings().isEmpty()) size += 2; // string_id and string_data
        if(!map.types().isEmpty()) size++;
        if(!map.protos().isEmpty()) size++;
        if(!map.fields().isEmpty()) size++;
        if(!map.methods().isEmpty()) size++;
        if(!map.callSites().isEmpty()) size++;
        if(!map.methodHandles().isEmpty()) size++;
        if(!map.typeLists().isEmpty()) size++;
        if(!map.encodedArrays().isEmpty()) size++;
        if(!map.annotations().isEmpty()) size++;
        if(!map.annotationSets().isEmpty()) size++;
        if(!map.annotationSetRefLists().isEmpty()) size++;
        if(!map.annotationsDirectories().isEmpty()) size++;
        if(map.hiddenApi() != null) size++;
        if(!map.debugInfos().isEmpty()) size++;
        if(!map.codes().isEmpty()) size++;
        if(!map.classDatas().isEmpty()) size++;
        if(!map.classes().isEmpty()) size++;

        return size;
    }

    private void writeMap(@NotNull DexMap value, @NotNull Sections sections, @NotNull WriteContext context) throws IOException {
        Output output = sections.map();
        output.writeInt(computeNumberItems(value));

        var offsets = context.offsets();

        writeMapEntry(output, TYPE_HEADER_ITEM, 1, 0);
        writeMapEntry(output, TYPE_STRING_ID_ITEM, value.strings().size(), offsets.get(sections.stringIds()));
        writeMapEntry(output, TYPE_TYPE_ID_ITEM, value.types().size(), offsets.get(sections.typeIds()));
        writeMapEntry(output, TYPE_PROTO_ID_ITEM, value.protos().size(), offsets.get(sections.protoIds()));
        writeMapEntry(output, TYPE_FIELD_ID_ITEM, value.fields().size(), offsets.get(sections.fieldIds()));
        writeMapEntry(output, TYPE_METHOD_ID_ITEM, value.methods().size(), offsets.get(sections.methodIds()));
        writeMapEntry(output, TYPE_CLASS_DEF_ITEM, value.classes().size(), offsets.get(sections.classDefs()));
        writeMapEntry(output, TYPE_CALL_SITE_ID_ITEM, value.callSites().size(),
                offsets.get(sections.callSiteIds()));
        writeMapEntry(output, TYPE_METHOD_HANDLE_ITEM, value.methodHandles().size(),
                offsets.get(sections.methodHandles()));

        // data section entries
        writeMapEntry(output, TYPE_STRING_DATA_ITEM, value.stringDatas(), context);
        writeMapEntry(output, TYPE_TYPE_LIST, value.typeLists(), context);
        writeMapEntry(output, TYPE_ENCODED_ARRAY_ITEM, value.encodedArrays(), context);
        writeMapEntry(output, TYPE_ANNOTATION_ITEM, value.annotations(), context);
        writeMapEntry(output, TYPE_ANNOTATION_SET_ITEM, value.annotationSets(), context);
        writeMapEntry(output, TYPE_ANNOTATION_SET_REF_LIST, value.annotationSetRefLists(), context);
        writeMapEntry(output, TYPE_ANNOTATIONS_DIRECTORY_ITEM, value.annotationsDirectories(), context);
        if (value.hiddenApi() != null) {
            writeMapEntry(output, TYPE_HIDDENAPI_CLASS_DATA_ITEM, value.hiddenApi().itemCount(),
                    offsets.get(sections.hiddenApi()));
        }
        writeMapEntry(output, TYPE_DEBUG_INFO_ITEM, value.debugInfos(), context);
        writeMapEntry(output, TYPE_CODE_ITEM, value.codes(), context);
        writeMapEntry(output, TYPE_CLASS_DATA_ITEM, value.classDatas(), context);

        // write myself
        writeMapEntry(output, TYPE_MAP_LIST, 1, offsets.get(sections.map()));
    }

    private void computeOffsets(@NotNull Sections sections, @NotNull WriteContext context) {
        var offsets = context.offsets();
        int offset = 0x70;

        offset = putOffset(sections.stringIds(), offsets, offset);
        offset = putOffset(sections.typeIds(), offsets, offset);
        offset = putOffset(sections.protoIds(), offsets, offset);
        offset = putOffset(sections.fieldIds(), offsets, offset);
        offset = putOffset(sections.methodIds(), offsets, offset);
        offset = putOffset(sections.classDefs(), offsets, offset);
        offset = putOffset(sections.callSiteIds(), offsets, offset);
        offset = putOffset(sections.methodHandles(), offsets, offset);
        offset = putOffset(sections.data(), offsets, offset);
        offset = putOffset(sections.hiddenApi(), offsets, offset);
        putOffset(sections.map(), offsets, offset);
    }

    private void writeBasic(@NotNull DexMap value, @NotNull Sections sections, @NotNull WriteContext context) throws IOException {
        // we place the typelists first to avoid having to write extra alignment bytes later
        for (TypeListItem typeList : value.typeLists()) {
            write(typeList, TypeListItem.CODEC, sections.data(), context);
        }

        for (StringDataItem stringData : value.stringDatas()) {
            write(stringData, StringDataItem.CODEC, sections.data(), context);
        }

        for (StringItem string : value.strings()) {
            write(string, StringItem.CODEC, sections.stringIds(), context);
        }

        for (TypeItem type : value.types()) {
            write(type, TypeItem.CODEC, sections.typeIds(), context);
        }

        for (ProtoItem proto : value.protos()) {
            write(proto, ProtoItem.CODEC, sections.protoIds(), context);
        }

        for (FieldItem field : value.fields()) {
            write(field, FieldItem.CODEC, sections.fieldIds(), context);
        }

        for (MethodItem method : value.methods()) {
            write(method, MethodItem.CODEC, sections.methodIds(), context);
        }
    }

    private void writeAnnotations(@NotNull DexMap value, @NotNull Sections sections, @NotNull WriteContext context) throws IOException {
        // encoded arrays depend on method handles
        for (MethodHandleItem methodHandle : value.methodHandles()) {
            write(methodHandle, MethodHandleItem.CODEC, sections.methodHandles(), context);
        }

        // callsites depend on encoded arrays
        for (EncodedArrayItem encodedArray : value.encodedArrays()) {
            write(encodedArray, EncodedArrayItem.CODEC, sections.data(), context);
        }

        for (CallSiteItem callSite : value.callSites()) {
            write(callSite.data(), CallSiteDataItem.CODEC, sections.data(), context);
            write(callSite, CallSiteItem.CODEC, sections.callSiteIds(), context);
        }

        for (AnnotationItem annotation : value.annotations()) {
            write(annotation, AnnotationItem.CODEC, sections.data(), context);
        }

        for (AnnotationSetItem annotationSet : value.annotationSets()) {
            write(annotationSet, AnnotationSetItem.CODEC, sections.data(), context);
        }

        for (AnnotationSetRefList annotationSetRefList : value.annotationSetRefLists()) {
            write(annotationSetRefList, AnnotationSetRefList.CODEC, sections.data(), context);
        }

        for (AnnotationsDirectoryItem annotationsDirectory : value.annotationsDirectories()) {
            write(annotationsDirectory, AnnotationsDirectoryItem.CODEC, sections.data(), context);
        }
    }

    private void writeClasses(@NotNull DexMap value, @NotNull Sections sections, @NotNull WriteContext context) throws IOException {
        for (DebugInfoItem debugInfo : value.debugInfos()) {
            write(debugInfo, DebugInfoItem.CODEC, sections.data(), context);
        }

        for (CodeItem code : value.codes()) {
            write(code, CodeItem.CODEC, sections.data(), context);
        }

        for (ClassDefItem classDef : value.classes()) {
            if (classDef.classData() != null)
                write(classDef.classData(), ClassDataItem.CODEC, sections.data(), context);
            write(classDef, ClassDefItem.CODEC, sections.classDefs(), context);
        }

        if (value.hiddenApi() != null)
            sections.hiddenApi().write(value.hiddenApi().payload());
    }

    private @NotNull WriteContext createContext(@NotNull DexMap value, @NotNull Map<Object, Integer> offsets) {
        int offset = 0x70; // we are after the header
        offset += value.strings().size() * 4; // string ids
        offset += value.types().size() * 4; // type ids
        offset += value.protos().size() * 12; // proto ids
        offset += value.fields().size() * 8; // field ids
        offset += value.methods().size() * 8; // method ids
        offset += value.classes().size() * 32; // class defs
        offset += value.callSites().size() * 4; // call sites
        offset += value.methodHandles().size() * 8; // method handles

        return new WriteContext(value, offsets, offset);
    }

    private @NotNull WriteState state() {
        return Objects.requireNonNull(writeState, "DexMapCodec write state is only available after write()");
    }

    private static int index(int type) {
        return (type & 15) + 8 * (type >>> 12);
    }

    /**
     * Read rank of a map entry type.
     *
     * <p>Items address a constant pool by index, and a pool gains its entries in the order its section is
     * read, so a section can only be decoded once every pool it indexes into is complete. The pools are
     * therefore read first, in the order they depend on each other; notably method handles precede call
     * sites, even though the map list stores {@code call_site_id_item} (0x0007) ahead of
     * {@code method_handle_item} (0x0008). Sections that define no pool share the last rank and keep their
     * file order, as they are only ever reached through offsets.
     *
     * @param type
     * 		Map entry type code.
     *
     * @return Rank of the section, lower ranks being read first.
     */
    private static int readRank(int type) {
        int rank = POOL_SECTIONS.indexOf(type);
        return rank < 0 ? POOL_SECTIONS.size() : rank;
    }

    static {
        ItemCodec<?>[] codecs = new ItemCodec[index(0x2006) + 1];
        codecs[index(1)] = StringItem.CODEC;
        codecs[index(2)] = TypeItem.CODEC;
        codecs[index(3)] = ProtoItem.CODEC;
        codecs[index(4)] = FieldItem.CODEC;
        codecs[index(5)] = MethodItem.CODEC;
        codecs[index(6)] = ClassDefItem.CODEC;
        codecs[index(7)] = CallSiteItem.CODEC;
        codecs[index(8)] = MethodHandleItem.CODEC;
        codecs[index(0x1001)] = TypeListItem.CODEC;
        codecs[index(0x1002)] = AnnotationSetRefList.CODEC;
        codecs[index(0x1003)] = AnnotationSetItem.CODEC;
        codecs[index(0x2000)] = ClassDataItem.CODEC;
        codecs[index(0x2001)] = CodeItem.CODEC;
        codecs[index(0x2002)] = StringDataItem.CODEC;
        codecs[index(0x2003)] = DebugInfoItem.CODEC;
        codecs[index(0x2004)] = AnnotationItem.CODEC;
        codecs[index(0x2005)] = EncodedArrayItem.CODEC;
        codecs[index(0x2006)] = AnnotationsDirectoryItem.CODEC;
        CODECS = codecs;
    }

    private record WriteState(@NotNull Sections sections, @NotNull Map<Object, Integer> offsets, int dataOffset) {}

    private record MapEntry(int type, long amount, int offset) {}
}
