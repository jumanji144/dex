package me.darknet.dex.io;

import org.jetbrains.annotations.NotNull;

public interface Slicable {

    @NotNull Slicable slice(int offset, int length);

    @NotNull Slicable slice(int offset);

}
