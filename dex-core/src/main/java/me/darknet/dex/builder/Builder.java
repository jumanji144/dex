package me.darknet.dex.builder;

import org.jetbrains.annotations.NotNull;

public interface Builder<T> {

    @NotNull T build();

}
