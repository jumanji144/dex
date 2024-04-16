package me.darknet.dex.collections;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Function;

public class ConstantPool<T> implements Iterable<T>, RandomAccess {

    private final Map<T, Integer> pool = new HashMap<>(16);
    private final List<T> items = new ArrayList<>(16);
    private final List<T> itemsView = Collections.unmodifiableList(items);

    private final Function<T, Integer> computeFunction = t -> {
        items.add(t);
        return pool.size();
    };

    public int add(T cst) {
        return pool.computeIfAbsent(cst, computeFunction);
    }

    public T get(int index) {
        return items.get(index);
    }

    public int size() {
        return items.size();
    }

    public int indexOf(T cst) {
        return pool.getOrDefault(cst, -1);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @NotNull
    @Override
    public Iterator<T> iterator() {
        return itemsView.iterator();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ConstantPool<?> that = (ConstantPool<?>) obj;
        return itemsView.equals(that.itemsView);
    }
}
