package me.darknet.dex.collections;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.function.Function;

/**
 * A pool of unique entries that can be looked up by index.
 *
 * @param <T>
 * 		Type of the entries in the pool.
 */
public class ConstantPool<T> implements Iterable<T>, RandomAccess {
	private final Map<T, Integer> pool = new HashMap<>(16);
	private final List<T> items = new ArrayList<>(16);
	private final List<T> itemsView = Collections.unmodifiableList(items);

	private final Function<T, Integer> computeFunction = t -> {
		items.add(t);
		return items.size() - 1;
	};

	/**
	 * @param cst
	 * 		Entry to add.
	 *
	 * @return Index of the entry in the pool.
	 */
	public int add(T cst) {
		return pool.computeIfAbsent(cst, computeFunction);
	}

	/**
	 * Reorders the entries and reassigns their indices.
	 *
	 * @param comparator
	 * 		Order to place the entries in.
	 */
	public void sort(Comparator<? super T> comparator) {
		// Indices are the position an entry ends up at, so every previously issued index is invalidated.
		//
		// Callers that have already written indices into items have to re-issue them by looking the entries up
		// again, which is why pools are only ordered before anything is encoded from them.
		items.sort(comparator);
		pool.clear();
		for (int index = 0; index < items.size(); index++) {
			pool.put(items.get(index), index);
		}
	}

	/**
	 * @param index
	 * 		Index of the entry to return.
	 *
	 * @return Entry at the given index.
	 */
	public T get(int index) {
		return items.get(index);
	}

	/**
	 * @return Number of entries in the pool.
	 */
	public int size() {
		return items.size();
	}

	/**
	 * @param cst
	 * 		Entry to check for.
	 *
	 * @return {@code true} if the entry is in the pool, {@code false} otherwise.
	 */
	public boolean contains(T cst) {
		return pool.containsKey(cst);
	}

	/**
	 * @param csts
	 * 		Entries to check for.
	 *
	 * @return {@code true} if all entries are in the pool, {@code false} otherwise.
	 */
	public boolean containsAll(Collection<T> csts) {
		return pool.keySet().containsAll(csts);
	}

	/**
	 * @param cst
	 * 		Entry to remove.
	 */
	public void remove(T cst) {
		pool.remove(cst);
		items.remove(cst);
	}

	/**
	 * @param csts
	 * 		Entries to remove.
	 */
	public void removeAll(Collection<T> csts) {
		pool.keySet().removeAll(csts);
		items.removeAll(csts);
	}

	/**
	 * Removes all entries from the pool.
	 */
	public void clear() {
		pool.clear();
		items.clear();
	}

	/**
	 * @param cst
	 * 		Entry to look up.
	 *
	 * @return Index of the entry in the pool, or {@code -1} if it is not present.
	 */
	public int indexOf(T cst) {
		return pool.getOrDefault(cst, -1);
	}

	/**
	 * @return {@code true} if the pool contains no entries, {@code false} otherwise.
	 */
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
