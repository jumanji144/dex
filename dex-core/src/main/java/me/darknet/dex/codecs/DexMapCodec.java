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

    /** Width of one map entry: type, unused, size and offset. */
    private static final int MAP_ENTRY_SIZE = 12;

    /**
     * @return Sections of the last written dex file.
     *
     * @throws IllegalStateException
     *         If no dex file has been written yet.
     */
    public Sections sections() {
        return state().sections();
    }

    /**
     * @return Offsets of every section and every item in the last written dex file.
     *
     * @throws IllegalStateException
     *         If no dex file has been written yet.
     */
    public Map<Object, Integer> offsets() {
        return state().offsets();
    }

    /**
     * @return Offset of the data section in the last written dex file.
     */
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

    @Override
    public void write(@NotNull DexMap value, @NotNull Output output) throws IOException {
        write(value, new byte[0], output);
    }

    /**
     * Writes every section of a dex file and fixes their layout.
     * 
     *
     * @param value
     *         Map to write.
     * @param link
     *         Link data, which is empty for every unlinked dex.
     * @param output
     *         Output to write into.
     *
     * @throws IOException
     *         If a section cannot be written.
     */
    public void write(@NotNull DexMap value, byte[] link, @NotNull Output output) throws IOException {
        Sections sections = new Sections(output);
        Map<Object, Integer> offsets = new HashMap<>();
        WriteContext context = createContext(value, offsets);

        writeBasic(value, sections, context);
        writeAnnotations(value, sections, context);
        writeClasses(value, sections, context);

        // The link data is written here rather than by the header codec so that the layout below can see its
        // size: a section whose size is still unknown when the layout is fixed would fall outside the file, and
        // the header would then describe a file smaller than its own map list.
        sections.link().writeBytes(link);

        // The map list's own size is known before its contents are: every entry is the same width and the
        // count only depends on which sections ended up non-empty. Reserving the space lets the layout count
        // the map, even though the entries cannot be written until the layout has told them their offsets.
        int mapSize = Integer.BYTES + MAP_ENTRY_SIZE * countMapEntries(value);
        sections.map().position(mapSize);

        layoutSections(sections, context);

        writeMap(value, sections, context);

        // The map has to fill exactly the space that was reserved, or the file size and the map list's own
        // offset would describe a file that does not exist.
        if (sections.map().position() != mapSize) {
            throw new IllegalStateException("Reserved " + mapSize + " bytes for the map list but wrote "
                    + sections.map().position());
        }

        writeState = new WriteState(sections, Map.copyOf(offsets), context.dataOffset());
    }

    /**
     * Counts the entries {@link #writeMap} will emit.
     *
     * @param map
     *         Map to count entries for.
     *
     * @return Number of map entries the file will contain.
     */
    private int countMapEntries(@NotNull DexMap map) {
        int size = 2; // header item and map list, which are always present

        if (!map.strings().isEmpty()) size++;
        if (!map.stringDatas().isEmpty()) size++;
        if (!map.types().isEmpty()) size++;
        if (!map.protos().isEmpty()) size++;
        if (!map.fields().isEmpty()) size++;
        if (!map.methods().isEmpty()) size++;
        if (!map.callSites().isEmpty()) size++;
        if (!map.methodHandles().isEmpty()) size++;
        if (!map.typeLists().isEmpty()) size++;
        // One entry covers both, since call site data is written inside the encoded array region.
        if (!map.encodedArrays().isEmpty() || !map.callSites().isEmpty()) size++;
        if (!map.annotations().isEmpty()) size++;
        if (!map.annotationSets().isEmpty()) size++;
        if (!map.annotationSetRefLists().isEmpty()) size++;
        if (!map.annotationsDirectories().isEmpty()) size++;
        if (map.hiddenApi() != null) size++;
        if (!map.debugInfos().isEmpty()) size++;
        if (!map.codes().isEmpty()) size++;
        if (!map.classDatas().isEmpty()) size++;
        if (!map.classes().isEmpty()) size++;

        return size;
    }

    private void writeMap(@NotNull DexMap value, @NotNull Sections sections, @NotNull WriteContext context) throws IOException {
        Output output = sections.map();

        // The map list is required to be ordered by the offset each entry describes, which is not the same
        // order the sections are written in: the data section holds several item kinds, and the order their
        // writers happen to run in does not follow the type codes.
        List<MapEntry> entries = new ArrayList<>();
        entries.add(new MapEntry(TYPE_HEADER_ITEM, 1, 0));

        // Pool section entries
        addMapEntry(entries, TYPE_STRING_ID_ITEM, value.strings().size(), sections.offsetOf(sections.stringIds()));
        addMapEntry(entries, TYPE_TYPE_ID_ITEM, value.types().size(), sections.offsetOf(sections.typeIds()));
        addMapEntry(entries, TYPE_PROTO_ID_ITEM, value.protos().size(), sections.offsetOf(sections.protoIds()));
        addMapEntry(entries, TYPE_FIELD_ID_ITEM, value.fields().size(), sections.offsetOf(sections.fieldIds()));
        addMapEntry(entries, TYPE_METHOD_ID_ITEM, value.methods().size(), sections.offsetOf(sections.methodIds()));
        addMapEntry(entries, TYPE_CLASS_DEF_ITEM, value.classes().size(), sections.offsetOf(sections.classDefs()));
        addMapEntry(entries, TYPE_CALL_SITE_ID_ITEM, value.callSites().size(), sections.offsetOf(sections.callSiteIds()));
        addMapEntry(entries, TYPE_METHOD_HANDLE_ITEM, value.methodHandles().size(), sections.offsetOf(sections.methodHandles()));

        // Data section entries
        addMapEntry(entries, TYPE_STRING_DATA_ITEM, value.stringDatas(), context);
        addMapEntry(entries, TYPE_TYPE_LIST, value.typeLists(), context);
        addEncodedArrayEntry(entries, value, context);
        addMapEntry(entries, TYPE_ANNOTATION_ITEM, value.annotations(), context);
        addMapEntry(entries, TYPE_ANNOTATION_SET_ITEM, value.annotationSets(), context);
        addMapEntry(entries, TYPE_ANNOTATION_SET_REF_LIST, value.annotationSetRefLists(), context);
        addMapEntry(entries, TYPE_ANNOTATIONS_DIRECTORY_ITEM, value.annotationsDirectories(), context);
        if (value.hiddenApi() != null) 
            addMapEntry(entries, TYPE_HIDDENAPI_CLASS_DATA_ITEM, value.hiddenApi().itemCount(), sections.offsetOf(sections.hiddenApi()));
        addMapEntry(entries, TYPE_DEBUG_INFO_ITEM, value.debugInfos(), context);
        addMapEntry(entries, TYPE_CODE_ITEM, value.codes(), context);
        addMapEntry(entries, TYPE_CLASS_DATA_ITEM, value.classDatas(), context);

        // The map list itself is the last section, so its entry orders last once the list is sorted.
        addMapEntry(entries, TYPE_MAP_LIST, 1, sections.offsetOf(sections.map()));

        // Sort the entries by the offset they describe, and then by type code to keep the file order of sections that share an offset.
        entries.sort(Comparator.comparingInt(MapEntry::offset).thenComparingInt(MapEntry::type));

        // The space was reserved before the layout ran, so the entries are written from the start of it.
        output.position(0);
        output.writeInt(entries.size());
        for (MapEntry entry : entries) {
            output.writeShort(entry.type());
            output.writeShort(0);
            output.writeInt((int) entry.amount());
            output.writeInt(entry.offset());
        }
    }

    /**
     * Records the map entry covering the encoded array region, which also holds the call site data.
     *
     * @param entries
     *         Entries to append to.
     * @param value
     *         Map being written.
     * @param context
     *         Write context holding the items' data-relative offsets.
     */
    private void addEncodedArrayEntry(@NotNull List<MapEntry> entries, @NotNull DexMap value,
                                      @NotNull WriteContext context) {
        int count = value.encodedArrays().size() + value.callSites().size();
        if (count == 0) 
            return;

        // Whichever of the two the data section holds first decides where the region begins.
        Object first = value.encodedArrays().isEmpty()
                ? value.callSites().get(0).data()
                : value.encodedArrays().get(0);
        entries.add(new MapEntry(TYPE_ENCODED_ARRAY_ITEM, count, context.offsets().get(first) + context.dataOffset()));
    }

    /**
     * Records a map entry for a section that starts at a fixed offset.
     *
     * @param entries
     *         Entries to append to.
     * @param type
     *         Map entry type code.
     * @param size
     *         Number of items in the section, which must be non-zero for the entry to exist.
     * @param offset
     *         Offset the section starts at.
     */
    private void addMapEntry(@NotNull List<MapEntry> entries, int type, int size, int offset) {
        if (size == 0) {
            return;
        }
        entries.add(new MapEntry(type, size, offset));
    }

    /**
     * Records a map entry for a pool of items written into the data section.
     *
     * @param entries
     *         Entries to append to.
     * @param type
     *         Map entry type code.
     * @param objects
     *         Pool holding the section's items, which must be non-empty for the entry to exist.
     * @param context
     *         Write context holding the items' data-relative offsets.
     */
    private <T> void addMapEntry(@NotNull List<MapEntry> entries, int type, @NotNull ConstantPool<T> objects,
                                 @NotNull WriteContext context) {
        if (objects.isEmpty()) {
            return;
        }
        addMapEntry(entries, type, objects.size(), context.offsets().get(objects.get(0)) + context.dataOffset());
    }

    /**
     * Fixes the file layout once every section's contents are final, and checks that the data section landed
     * where the write context predicted.
     *
     * @param sections
     *         Sections to lay out.
     * @param context
     *         Write context that sized the data section up front from the pool counts.
     */
    private void layoutSections(@NotNull Sections sections, @NotNull WriteContext context) {
        sections.layout(Sections.HEADER_SIZE);

        // The context has to know the data section's start before any data item is written, because items
        // address each other through offsets relative to it, so it derives that offset from the pool counts.
        // Every section ahead of the data section holds fixed-width entries, so the two derivations must
        // agree; if they ever do not, the counts and the written entries have drifted apart.
        if (sections.offsetOf(sections.data()) != context.dataOffset()) {
            throw new IllegalStateException("Data section starts at " + sections.offsetOf(sections.data())
                    + " but the write context sized it at " + context.dataOffset());
        }

        // Keep the section starts reachable through the offsets map as well, since callers read them back.
        var offsets = context.offsets();
        offsets.put(sections.stringIds(), sections.offsetOf(sections.stringIds()));
        offsets.put(sections.typeIds(), sections.offsetOf(sections.typeIds()));
        offsets.put(sections.protoIds(), sections.offsetOf(sections.protoIds()));
        offsets.put(sections.fieldIds(), sections.offsetOf(sections.fieldIds()));
        offsets.put(sections.methodIds(), sections.offsetOf(sections.methodIds()));
        offsets.put(sections.classDefs(), sections.offsetOf(sections.classDefs()));
        offsets.put(sections.callSiteIds(), sections.offsetOf(sections.callSiteIds()));
        offsets.put(sections.methodHandles(), sections.offsetOf(sections.methodHandles()));
        offsets.put(sections.data(), sections.offsetOf(sections.data()));
        offsets.put(sections.hiddenApi(), sections.offsetOf(sections.hiddenApi()));
        offsets.put(sections.map(), sections.offsetOf(sections.map()));
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
        int offset = Sections.HEADER_SIZE; // we are after the header
        offset += value.strings().size() * 4; // string ids
        offset += value.types().size() * 4; // type ids
        offset += value.protos().size() * 12; // proto ids
        offset += value.fields().size() * 8; // field ids
        offset += value.methods().size() * 8; // method ids
        offset += value.classes().size() * 32; // class defs
        offset += value.callSites().size() * 4; // call sites
        offset += value.methodHandles().size() * 8; // method handles

        // The sections ahead of the data section all hold fixed-width entries, so they are already word
        // aligned and this only guards against a future section that is not.
        offset = Sections.align(offset, 4);

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
     * <p>
     * Items address a constant pool by index, and a pool gains its entries in the order its section is
     * read, so a section can only be decoded once every pool it indexes into is complete. The pools are
     * therefore read first, in the order they depend on each other; notably method handles precede call
     * sites, even though the map list stores {@code call_site_id_item} (0x0007) ahead of
     * {@code method_handle_item} (0x0008). Sections that define no pool share the last rank and keep their
     * file order, as they are only ever reached through offsets.
     *
     * @param type
     *         Map entry type code.
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
