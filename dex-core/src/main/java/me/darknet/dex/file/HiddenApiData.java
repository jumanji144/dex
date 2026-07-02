package me.darknet.dex.file;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public record HiddenApiData(int itemCount, byte @NotNull [] payload) {
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public int size() {
        return payload.length;
    }
}
