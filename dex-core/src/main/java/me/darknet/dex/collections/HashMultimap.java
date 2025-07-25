package me.darknet.dex.collections;

import org.jetbrains.annotations.NotNull;

import java.util.*;

public class HashMultimap<K, V> implements Multimap<K, V> {
    private final static int AVERAGE_VALUES_PER_KEY = 2;
    private final int expectedValuesPerKey;
    private final Map<K, Collection<V>> map;
    private int size;

    public HashMultimap() {
        this(10, AVERAGE_VALUES_PER_KEY);
    }


    public HashMultimap(int expectedKeys) {
        this(expectedKeys, AVERAGE_VALUES_PER_KEY);
    }

    public HashMultimap(int expectedKeys, int expectedValuesPerKey) {
        this.expectedValuesPerKey = expectedValuesPerKey;
        map = new HashMap<>(expectedKeys);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean containsKey(K key) {
        return map.containsKey(key);
    }

    @Override
    public boolean containsValue(V value) {
        for (Collection<V> vs : map.values()) {
            if (vs.contains(value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsEntry(K key, V value) {
        Collection<V> values = map.get(key);
        return values != null && values.contains(value);
    }

    @Override
    public boolean put(K key, V value) {
        Collection<V> values = map.get(key);
        if (values == null) {
            values = createCollection(key);
            if(values.add(value)) {
                size++;
                map.put(key, values);
                return true;
            }
        } else {
            if(values.add(value)) {
                size++;
                return true;
            }
        }
        return false;
    }

    private @NotNull Collection<V> createCollection(K key) {
        return new HashSet<>(expectedValuesPerKey);
    }

    @Override
    public boolean remove(K key, V value) {
        Collection<V> values = map.get(key);
        return values != null && values.remove(value);
    }

    @Override
    public boolean removeAll(K key) {
        if (containsKey(key)) {
            get(key).clear();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {

    }

    @Override
    public @NotNull Collection<V> get(K key) {
	    return map.computeIfAbsent(key, this::createCollection);
    }

    @Override
    public @NotNull Collection<V> values() {
        HashSet<V> values = new HashSet<>(size);
        for (Collection<V> vs : map.values())
            values.addAll(vs);
        return values;
    }

    @Override
    public @NotNull Collection<K> keys() {
        return map.keySet();
    }

    @Override
    public @NotNull Collection<Map.Entry<K, V>> entries() {
        HashSet<Map.Entry<K, V>> entries = new HashSet<>(size);
        for (Map.Entry<K, Collection<V>> entry : map.entrySet()) {
            for (V value : entry.getValue()) {
                entries.add(new Node<>(entry.getKey(), value));
            }
        }
        return entries;
    }

    @Override
    public @NotNull Map<K, Collection<V>> asMap() {
        return map;
    }

    private static class Node<K,V> implements Map.Entry<K,V> {
        final K key;
        V value;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public final K getKey()        { return key; }
        public final V getValue()      { return value; }
        public final String toString() { return key + "=" + value; }

        public final int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }

        public final V setValue(V newValue) {
            V oldValue = value;
            value = newValue;
            return oldValue;
        }

        public final boolean equals(Object o) {
            if (o == this)
                return true;

            return o instanceof Map.Entry<?, ?> e
                    && Objects.equals(key, e.getKey())
                    && Objects.equals(value, e.getValue());
        }
    }
}
