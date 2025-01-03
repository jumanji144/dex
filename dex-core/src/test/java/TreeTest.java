import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.IOException;

public class TreeTest {

    @Test
    public void testSimpleDefinition() throws IOException {
        // read dex file
        Input input = Input.wrap(getClass().getResourceAsStream("/samples/008-exceptions/classes.dex").readAllBytes());
        Output output = Output.wrap();
        DexHeaderCodec codec = new DexHeaderCodec();

        DexFile dexFile = DexFile.CODEC.map(codec.read(input));

        DexHeader newHeader = DexFile.CODEC.unmap(dexFile);

        codec.write(newHeader, output);

        input = Input.wrap(output);

        DexFile newDexFile = DexFile.CODEC.map(codec.read(input));
        return;
    }

}
