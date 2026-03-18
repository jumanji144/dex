package me.darknet.dex;

import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.util.DexSource;
import me.darknet.dex.util.TestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class RoundTripTest {

    @ParameterizedTest
    @MethodSource("getDexInputs")
    void testRoundTrip(DexSource argument) throws Throwable {
        Input input = Input.wrap(argument.source().get().readAllBytes());
        DexHeaderCodec codec = DexHeader.CODEC;
        DexHeader header = codec.read(input);

        Output output = Output.wrap();
        codec.write(header, output);

        // write to file
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        output.pipe(baos);

        input = Input.wrap(output.buffer());
        header = codec.read(input);
    }

    private static List<DexSource> getDexInputs() {
        return TestUtils.getDexInputs();
    }
}
