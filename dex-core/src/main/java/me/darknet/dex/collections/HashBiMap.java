package me.darknet.dex.collections;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HashBiMap<K, V> implements BiMap<K, V> {

    private Entry<K, V>[] KtoVTable;
    private Entry<K, V>[] VtoKTable;

    private int size;
    private int capacity;

    private int mask;

    private final Inverse inverse = new Inverse();

    public HashBiMap() {
        this(16);
    }

    public HashBiMap(int capacity) {
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.size = 0;
        this.KtoVTable = new Entry[capacity];
        this.VtoKTable = new Entry[capacity];
    }

    private int hash(Object o) {
        int hash;
        return o == null ? 0 : (hash = o.hashCode()) ^ (hash >>> 16);
    }

    private Entry<K, V> seekByKey(K key) {
        // algorithm taken from HashMap
        int hash = hash(key);
        Entry<K, V> first = KtoVTable[hash & mask];
        K k;
        if (first == null) {
            return null;
        }
        if (first.keyHash == hash &&
                ((k = first.getKey()) == key || (key != null && key.equals(k)))) {
            return first;
        }
        // search for key
        Entry<K, V> entry = first.next;
        if(entry != null) {
            do {
                if (entry.keyHash == hash &&
                        ((k = entry.getKey()) == key || (key != null && key.equals(k)))) {
                    return entry;
                }
            } while ((entry = entry.next) != null);
        }
        return null;
    }

    private Entry<K, V> seekByValue(V value) {
        // algorithm taken from HashMap
        int hash = hash(value);
        Entry<K, V> first = VtoKTable[hash & mask];
        V v;
        if (first == null) {
            return null;
        }
        if (first.valueHash == hash &&
                ((v = first.getValue()) == value || (value != null && value.equals(v)))) {
            return first;
        }
        // search for value
        Entry<K, V> entry = first.next;
        if(entry != null) {
            do {
                if (entry.valueHash == hash &&
                        ((v = entry.getValue()) == value || (value != null && value.equals(v)))) {
                    return entry;
                }
            } while ((entry = entry.next) != null);
        }
        return null;
    }

    private V putKV(K key, V value) {
        int keyHash = hash(key);
        int valueHash = hash(value);

        Entry<K, V> oldKeyEntry = seekByKey(key);
        if (oldKeyEntry != null
                && valueHash == oldKeyEntry.valueHash
                && value.equals(oldKeyEntry.getValue())) {
            // identical insert
            return value;
        }

        Entry<K, V> oldValueEntry = seekByValue(value);
        if (oldValueEntry != null) {
            throw new IllegalArgumentException("Value already exists in map");
        }

        Entry<K, V> newEntry = new Entry<>(key, value, keyHash, valueHash);
        if (oldKeyEntry != null) {
            // remove old key
            delete(oldKeyEntry);
            insert(newEntry);
            return oldKeyEntry.getValue();
        } else {
            // insert new key
            insert(newEntry);
            maybeResize();
            return null;
        }
    }

    private K putVK(V value, K key) {
        int valueHash = hash(value);
        int keyHash = hash(key);

        Entry<K, V> oldValueEntry = seekByValue(value);
        Entry<K, V> oldKeyEntry = seekByKey(key);
        if (oldValueEntry != null
                && keyHash == oldValueEntry.keyHash
                && key.equals(oldValueEntry.getKey())) {
            // identical insert
            return key;
        }
        if (oldKeyEntry != null) {
            throw new IllegalArgumentException("Key already exists in map");
        }

        if (oldValueEntry != null)
            delete(oldValueEntry);

        Entry<K, V> newEntry = new Entry<>(key, value, keyHash, valueHash);
        insert(newEntry);

        maybeResize();

        return oldValueEntry == null ? null : oldValueEntry.getKey();
    }

    private void delete(Entry<K, V> entry) {
        int keyHash = entry.keyHash;
        int valueHash = entry.valueHash;

        deleteFromTable(entry, keyHash, KtoVTable);

        deleteFromTable(entry, valueHash, VtoKTable);

        size--;
    }

    private void deleteFromTable(Entry<K, V> entry, int hash, Entry<K, V>[] table) {
        Entry<K, V> valueEntry = table[hash & mask];
        if (valueEntry == entry) {
            table[hash & mask] = entry.next;
        } else {
            Entry<K, V> prev;
            Entry<K, V> next;
            while ((prev = valueEntry) != null && (next = valueEntry.next) != null) {
                if (next == entry) {
                    prev.next = entry.next;
                    break;
                }
            }
        }
    }

    private void insert(Entry<K, V> entry) {
        insertInto(KtoVTable, VtoKTable, entry);
    }

    private void maybeResize() {
        if (size > capacity * 0.75) {
            resize(capacity * 2);
        }
    }

    private void resize(int newCapacity) {
        Entry<K, V>[] newKtoVTable = new Entry[newCapacity];
        Entry<K, V>[] newVtoKTable = new Entry[newCapacity];
        int newMask = newCapacity - 1;

        for (Entry<K, V> entry : KtoVTable) {
            if (entry != null) {
                insertInto(newKtoVTable, newVtoKTable, entry);
            }
        }

        this.capacity = newCapacity;
        this.mask = newMask;
        this.KtoVTable = newKtoVTable;
        this.VtoKTable = newVtoKTable;
    }

    private void insertInto(Entry<K, V>[] newKtoVTable, Entry<K, V>[] newVtoKTable, Entry<K, V> entry) {
        int keyHash = entry.keyHash;
        int valueHash = entry.valueHash;

        Entry<K, V> keyEntry = newKtoVTable[keyHash];
        if (keyEntry != null) {
            entry.next = keyEntry;
            keyEntry.prev = entry;
        }
        newKtoVTable[keyHash & mask] = entry;

        Entry<K, V> valueEntry = newVtoKTable[valueHash & mask];
        if (valueEntry != null) {
            entry.next = valueEntry;
            valueEntry.prev = entry;
        }
        newVtoKTable[valueHash & mask] = entry;

        size++;
    }

    @Override
    public @NotNull BiMap<V, K> inverse() {
        return inverse;
    }

    @Override
    public boolean containsKey(Object key) {
        return seekByKey((K) key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        return seekByValue((V) value) != null;
    }

    @Override
    public V get(Object key) {
        Entry<K, V> entry = seekByKey((K) key);
        return entry == null ? null : entry.getValue();
    }

    @Override
    public K getKey(V value) {
        Entry<K, V> entry = seekByValue(value);
        return entry == null ? null : entry.getKey();
    }

    @Override
    public V put(K key, V value) {
        return putKV(key, value);
    }

    @Override
    public K putValue(V key, K value) {
        return putVK(key, value);
    }

    @Override
    public V remove(Object key) {
        Entry<K, V> entry = seekByKey((K) key);
        if (entry != null) {
            delete(entry);
            return entry.getValue();
        }
        return null;
    }

    @Override
    public void putAll(@NotNull Map<? extends K, ? extends V> m) {
        for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public K removeValue(V value) {
        Entry<K, V> entry = seekByValue(value);
        if (entry != null) {
            delete(entry);
            return entry.getKey();
        }
        return null;
    }

    @Override
    public void clear() {
        for (Entry<K, V> entry : KtoVTable) {
            if (entry != null) {
                delete(entry);
            }
        }
    }

    @NotNull
    @Override
    public Set<K> keySet() {
        return Set.of();
    }

    @NotNull
    @Override
    public Collection<V> values() {
        return List.of();
    }

    @NotNull
    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return Set.of();
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    class Inverse implements BiMap<V, K> {

        @Override
        public @NotNull BiMap<K, V> inverse() {
            return HashBiMap.this;
        }

        @Override
        public boolean containsKey(Object key) {
            return HashBiMap.this.containsValue(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return HashBiMap.this.containsKey(value);
        }

        @Override
        public K get(Object key) {
            return HashBiMap.this.getKey((V) key);
        }

        @Override
        public V getKey(K value) {
            return HashBiMap.this.get(value);
        }

        @Override
        public K put(V key, K value) {
            return HashBiMap.this.putValue(key, value);
        }

        @Override
        public V putValue(K key, V value) {
            return HashBiMap.this.put(key, value);
        }

        @Override
        public K remove(Object key) {
            return HashBiMap.this.removeValue((V) key);
        }

        @Override
        public void putAll(@NotNull Map<? extends V, ? extends K> m) {

        }

        @Override
        public V removeValue(K value) {
            return HashBiMap.this.remove(value);
        }

        @Override
        public void clear() {
            HashBiMap.this.clear();
        }

        @NotNull
        @Override
        public Set<V> keySet() {
            return Set.of();
        }

        @NotNull
        @Override
        public Collection<K> values() {
            return List.of();
        }

        @NotNull
        @Override
        public Set<Entry<V, K>> entrySet() {
            return Set.of();
        }

        @Override
        public int size() {
            return HashBiMap.this.size();
        }

        @Override
        public boolean isEmpty() {
            return HashBiMap.this.isEmpty();
        }

    }

    static class Entry<K, V> {
        int valueHash;
        int keyHash;

        K key;
        V value;

        Entry<K, V> next;
        Entry<K, V> prev;

        Entry(K key, V value, int keyHash, int valueHash) {
            this.key = key;
            this.value = value;
            this.keyHash = keyHash;
            this.valueHash = valueHash;
        }

        K getKey() {
            return key;
        }

        V getValue() {
            return value;
        }

        void setKey(K key) {
            this.key = key;
        }

        void setValue(V value) {
            this.value = value;
        }
    }
}
