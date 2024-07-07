package me.darknet.dex.collections;

import java.util.Map;

public interface BiMap<K, V> extends Map<K, V> {

    BiMap<V, K> inverse();

    boolean containsValue(Object value);

    K getKey(V value);

    K putValue(V key, K value);

    K removeValue(V value);

}
