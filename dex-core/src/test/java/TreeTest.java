import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.instructions.Binary2AddrInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryOperation;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class TreeTest {

    @Test
    public void testSimpleDefinition() throws IOException {
        // read dex file
        Input input = Input.wrap(getClass().getResourceAsStream("/samples/002-sleep/classes.dex").readAllBytes());
        DexHeader header = new DexHeaderCodec().read(input);

        // get the class
        ClassDefItem classDef = header.map().classes().get(0);

        ClassDefinition definition = ClassDefinition.CODEC.map(classDef, header.map());

        new BinaryInstruction(BinaryOperation.ADD, Types.INT, 0,1, 2);
        new Binary2AddrInstruction(BinaryOperation.ADD, Types.INT, 1, 2);
        new BinaryLiteralInstruction(BinaryOperation.ADD, 1, 2, 3);

        return;
    }

}
