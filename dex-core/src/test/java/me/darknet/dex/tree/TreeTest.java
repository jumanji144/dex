package me.darknet.dex.tree;

import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.util.DexSource;
import me.darknet.dex.util.TestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

public class TreeTest {
    @ParameterizedTest
    @MethodSource("getClasses")
    void testRoundTrip(DexSource argument) throws Throwable {
        Input input = Input.wrap(argument.source().get().readAllBytes());
        DexHeaderCodec codec = DexHeader.CODEC;
        DexHeader header = codec.read(input);

        DexMap map = header.map();
        DexFile dexFile = DexFile.CODEC.map(header, map);

        Output output = Output.wrap();
        var out = DexFile.CODEC.unmap(dexFile, new DexMapBuilder());

        codec.write(out, output);
    }

    private static List<DexSource> getClasses() {
        return TestUtils.getDexInputs();
    }
}
