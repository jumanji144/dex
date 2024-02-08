package me.darknet.dex.codecs;

import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.items.*;
import me.darknet.dex.io.Codec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.Map;

public class DexMapCodec implements Codec<DexMap>, ItemTypes {

    private final static Map<Integer, ItemCodec<?>> CODECS = Map.ofEntries(
            Map.entry(1, StringItem.CODEC), Map.entry(2, TypeItem.CODEC),
            Map.entry(3, ProtoItem.CODEC), Map.entry(4, FieldItem.CODEC),
            Map.entry(5, MethodItem.CODEC), Map.entry(6, ClassDefItem.CODEC),
            Map.entry(7, CallSiteItem.CODEC), Map.entry(8, MethodHandleItem.CODEC),
            Map.entry(0x1001, TypeListItem.CODEC), Map.entry(0x1002, AnnotationSetRefList.CODEC),
            Map.entry(0x1003, AnnotationSetItem.CODEC), Map.entry(0x2000, ClassDataItem.CODEC),
            Map.entry(0x2001, CodeItem.CODEC), Map.entry(0x2002, StringDataItem.CODEC),
            Map.entry(0x2003, DebugInfoItem.CODEC), Map.entry(0x2004, AnnotationItem.CODEC),
            Map.entry(0x2005, EncodedArrayItem.CODEC), Map.entry(0x2006, AnnotationsDirectoryItem.CODEC)
    );

    @Override
    public DexMap read(Input input) throws IOException {
        DexMapBuilder builder = new DexMapBuilder();
        long size = input.readUnsignedInt();
        for (long i = 0; i < size; i++) {
            int type = input.readUnsignedShort();
            input.readUnsignedShort(); // unused
            long amount = input.readUnsignedInt();
            int offset = input.readInt();
            if(type == TYPE_HEADER_ITEM || type == TYPE_MAP_LIST) { // is either header or map list
                continue;
            }
            Input slice = input.slice(offset);
            for (long j = 0; j < amount; j++) {
                Item item = CODECS.get(type).read(slice, builder);
                builder.add(item);
            }
        }
        return builder.build();
    }

    @Override
    public void write(DexMap value, Output output) throws IOException {
        // TODO: to write we prepare a byte buffer and pass in 2 buffers, one is the normal output buffer,
        //  and another is the data buffer, such data items can write their data to the data buffer. at
        //  this point we should be in the header at the `map_off` position, we seek our output to 0x70 (the end of the
        //  header buffer, and pass that buffer as the normal section to the items (where all the defs live).
        //  and then another data buffer that the items get access to write to the data section. after we are done
        //  writing the items, we write the map list to the data buffer and then seek back to the `map_off` position
        //  in the header and write the map list position into it. the header can then query some of the offsets
        //  created by this write function to write the offsets to the sections.
    }
}