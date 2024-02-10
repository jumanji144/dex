package me.darknet.dex.file;

public record DexHeader(String version, byte[] link, DexMap map) {

    @Override
    public String toString() {
        return "DexHeader[]";
    }
}
