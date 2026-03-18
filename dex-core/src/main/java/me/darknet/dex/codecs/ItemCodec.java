package me.darknet.dex.codecs;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.Item;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public abstract class ItemCodec<I extends Item> implements ContextCodec<I, DexMapAccess, WriteContext> {

    private static final ThreadLocal<Map<Integer, CacheEntry>> ITEM_CACHE = new ThreadLocal<>();

    public abstract I read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException;

    public abstract void write0(I value, @NotNull Output output, @NotNull WriteContext context) throws IOException;

    public int alignment() {
        return 1;
    }

    public static void clearCache() {
        Map<Integer, CacheEntry> cache = ITEM_CACHE.get();
        if (cache != null) {
            cache.clear();
        }
    }

    public static Object getCacheLock() {
        return ItemCodec.class;
    }

    static <T> T withFreshCache(@NotNull IOSupplier<T> action) throws IOException {
        Map<Integer, CacheEntry> previous = ITEM_CACHE.get();
        ITEM_CACHE.set(new HashMap<>(1 << 16));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ITEM_CACHE.remove();
            } else {
                ITEM_CACHE.set(previous);
            }
        }
    }

    @Override
    public I read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
        Map<Integer, CacheEntry> cache = ITEM_CACHE.get();
        if (cache == null) {
            return readUncached(input, context);
        }

        int position = input.position();
        CacheEntry cached = cache.get(position);
        if (cached != null) {
            input.position(cached.position);
            return (I) cached.item;
        }
        I item = readUncached(input, context);
        int result = input.position();
        cache.put(position, new CacheEntry(item, result));
        return item;
    }

    @Override
    public void write(I value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
        // align
        int position = output.position();
        position = (position + alignment() - 1) & -alignment();
        output.position(position);
        write0(value, output, context);
    }

    private I readUncached(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
        I item = read0(input, context);
        int result = input.position();
        // align
        result = (result + alignment() - 1) & -alignment();
        input.position(result);
        return item;
    }

    @FunctionalInterface
    interface IOSupplier<T> {
        T get() throws IOException;
    }

    record CacheEntry(@NotNull Item item, int position) {}

}
