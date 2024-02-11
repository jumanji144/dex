package me.darknet.dex.collections;

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

    Collection<V> get(K key);

    Collection<V> values();

    Collection<K> keys();

    Collection<Map.Entry<K, V>> entries();

    Map<K, Collection<V>> asMap();
}
