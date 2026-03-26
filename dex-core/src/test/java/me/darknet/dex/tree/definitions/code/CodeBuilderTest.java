package me.darknet.dex.tree.definitions.code;

import me.darknet.dex.tree.definitions.instructions.ConstStringInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CodeBuilderTest {
    @Test
    void buildUsesConfiguredCodeShape() {
        Code code = new CodeBuilder()
                .registers(2)
                .arguments(1, 3)
                .const_string(0, "hello")
                .return_void()
                .build();

        assertEquals(1, code.getIn());
        assertEquals(3, code.getOut());
        assertEquals(2, code.getRegisters());
        assertEquals(2, code.getInstructions().size());
        assertInstanceOf(ConstStringInstruction.class, code.getInstructions().get(0));
        assertInstanceOf(ReturnInstruction.class, code.getInstructions().get(1));
    }
}
