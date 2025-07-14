import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

public class RoundTripTest {

    private static final String PATH_PREFIX = "src/test/resources/samples/";

    @ParameterizedTest
    @MethodSource("getClasses")
    void testRoundTrip(TestArgument argument) throws Throwable {
        Input input = Input.wrap(argument.source().get().readAllBytes());
        DexHeaderCodec codec = new DexHeaderCodec();
        DexHeader header = codec.read(input);

        Output output = Output.wrap();
        codec.write(header, output);

        // write to file
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        output.pipe(baos);

        input = Input.wrap(output.buffer());
        header = codec.read(input);
    }

    private static List<TestArgument> getClasses() {
        try {
            BiPredicate<Path, BasicFileAttributes> filter = (path, attrib) -> attrib.isRegularFile()
                    && path.toString().endsWith(".dex");
            return Files.find(Paths.get(System.getProperty("user.dir")).resolve("src"), 25, filter)
                    .map(TestArgument::from).collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private record TestArgument(String name, ThrowingSupplier<InputStream> source) {
        public static TestArgument fromName(String name) {
            Path path = Paths.get(System.getProperty("user.dir")).resolve(PATH_PREFIX).resolve(name);
            return from(path);
        }

        public static TestArgument from(Path path) {
            return new TestArgument(path.getParent().getFileName() + "/" + path.getFileName().toString(),
                    () -> Files.newInputStream(path));
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
