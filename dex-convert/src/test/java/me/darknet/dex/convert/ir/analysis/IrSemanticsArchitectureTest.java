package me.darknet.dex.convert.ir.analysis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Keeps JVM lowering from becoming a second semantic authority. */
class IrSemanticsArchitectureTest {
    @Test
    void loweringDoesNotRecomputeDexOperandCategories() throws Exception {
        Path root = Path.of("src/main/java/me/darknet/dex/convert/ir/lowering");
        for (String file : new String[]{"IrOperationEmitter.java", "IrEffectEmitter.java", "IrLoweringEngine.java"}) {
            String source = Files.readString(root.resolve(file));
            assertFalse(source.contains("InstructionSemantics"), file);
            assertFalse(source.contains("operandTypeFor"), file);
            assertFalse(source.contains("referenceExpectedType"), file);
            assertFalse(source.contains("invokeInputType"), file);
        }
    }
}
