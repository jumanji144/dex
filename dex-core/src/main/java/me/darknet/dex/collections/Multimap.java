package me.darknet.dex.collections;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;

public interface Multimap<K extends Object, V extends Object>  {

    int size();

    boolean isEmpty();

    boolean containsKey(K key);

    boolean containsValue(V value);

    boolean containsEntry(K key, V value);

    boolean put(K key, V value);

    boolean remove(K key, V value);

    boolean removeAll(K key);

    void clear();

    @NotNull Collection<V> get(K key);

    @NotNull Collection<V> values();

    @NotNull Collection<K> keys();

    @NotNull Collection<Map.Entry<K, V>> entries();

    @NotNull Map<K, Collection<V>> asMap();
}
