package me.darknet.dex.file.value;

public record MethodHandleValue() implements Value {

    @Override
    public int type() {
        return 0x16;
    }
}
