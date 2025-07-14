package me.darknet.dex.io;

import org.jetbrains.annotations.NotNull;

public interface Seekable {

    int position();

    @NotNull Seekable position(int position);

    @NotNull Seekable seek(int offset);

}
