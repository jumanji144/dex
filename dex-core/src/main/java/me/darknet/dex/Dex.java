package me.darknet.dex;

import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.io.Input;

import java.io.IOException;
import java.io.InputStream;

public class Dex {

    public static void main(String[] args) throws IOException {
        InputStream stream = Dex.class.getClassLoader().getResourceAsStream("classes.dex");
        DexHeaderCodec codec = new DexHeaderCodec();
        DexHeader builder = codec.read(Input.wrap(stream.readAllBytes()));
        return;
    }

}
