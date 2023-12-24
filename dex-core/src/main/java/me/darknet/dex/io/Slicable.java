package me.darknet.dex.io;

public interface Slicable {

    Slicable slice(int offset, int length);

    Slicable slice(int offset);

}
