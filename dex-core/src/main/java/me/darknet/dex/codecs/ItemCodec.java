package me.darknet.dex.codecs;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.Item;
import me.darknet.dex.io.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public abstract class ItemCodec<I extends Item> implements ContextCodec<I, DexMapAccess, WriteContext> {

    private static final Map<Integer, CacheEntry> ITEM_CACHE = new HashMap<>();

    public abstract I read0(Input input, DexMapAccess context) throws IOException;

    public abstract void write0(I value, Output output, WriteContext context) throws IOException;

    public int alignment() {
        return 1;
    }

    @Override
    public I read(Input input, DexMapAccess context) throws IOException {
        int position = input.position();
        CacheEntry cached = ITEM_CACHE.get(position);
        if (cached != null) {
            input.position(cached.position);
            return (I) cached.item;
        }
        I item = read0(input, context);
        int result = input.position();
        // align
        result = (result + alignment() - 1) & -alignment();
        ITEM_CACHE.put(position, new CacheEntry(item, result));
        return item;
    }

    @Override
    public void write(I value, Output output, WriteContext context) throws IOException {
        // align
        int position = output.position();
        position = (position + alignment() - 1) & -alignment();
        output.position(position);
        write0(value, output, context);
    }

    record CacheEntry(Item item, int position) {}

}
