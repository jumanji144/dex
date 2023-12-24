package me.darknet.dex.io;

public interface Seekable {

    int position();

    Seekable position(int position);

    Seekable seek(int offset);

}
