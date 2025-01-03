package me.darknet.dex.collections;

import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ImmutableCollections {

    public static <T> List<T> emptyList(int size) {
        if (size == 0) {
            return Collections.emptyList();
        }
        return new ImmutableEmptyNList<>(size);
    }

    private record ImmutableEmptyNList<E>(int size) implements List<E> {

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        @Override
        public boolean contains(Object o) {
            return false;
        }

        @Override
        public @NotNull Iterator<E> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public @NotNull Object[] toArray() {
            return new Object[size];
        }

        @Override
        public @NotNull <T> T[] toArray(@NotNull T[] a) {
            return a;
        }

        @Override
        public boolean add(E e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean containsAll(@NotNull Collection<?> c) {
            return false;
        }

        @Override
        public boolean addAll(@NotNull Collection<? extends E> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean addAll(int index, @NotNull Collection<? extends E> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(@NotNull Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(@NotNull Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {

        }

        @Override
        public E get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException();
            }
            return null;
        }

        @Override
        public E set(int index, E element) {

            return null;
        }

        @Override
        public void add(int index, E element) {
            throw new UnsupportedOperationException();
        }

        @Override
        public E remove(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int indexOf(Object o) {
            return -1;
        }

        @Override
        public int lastIndexOf(Object o) {
            return -1;
        }

        @Override
        public @NotNull ListIterator<E> listIterator() {
            return Collections.emptyListIterator();
        }

        @Override
        public @NotNull ListIterator<E> listIterator(int index) {
            return Collections.emptyListIterator();
        }

        @Override
        public @NotNull List<E> subList(int fromIndex, int toIndex) {
            return new ImmutableEmptyNList<>(toIndex - fromIndex);
        }
    }

}
