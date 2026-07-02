package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.Format00opAAAA;
import me.darknet.dex.file.instructions.Format00opAAAA32;
import me.darknet.dex.file.instructions.FormatAAop;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GotoInstructionTest {

    @Test
    void gotoUsesOneCodeUnit() {
        GotoInstruction instruction = new GotoInstruction(GotoInstruction.GOTO, new Label(0, 1));
        assertEquals(1, instruction.unitSize(), "GOTO should be 1 code unit");

        Format format = GotoInstruction.CODEC.unmap(instruction, unmapContext(instruction));
        assertInstanceOf(FormatAAop.class, format);
    }

    @Test
    void goto16UsesTwoCodeUnits() {
        GotoInstruction instruction = new GotoInstruction(GotoInstruction.GOTO_16, new Label(0, 0x100));
        assertEquals(2, instruction.unitSize(), "GOTO_16 should be 2 code units");

        Format format = GotoInstruction.CODEC.unmap(instruction, unmapContext(instruction));
        assertInstanceOf(Format00opAAAA.class, format);
    }

    @Test
    void goto32UsesThreeCodeUnits() {
        GotoInstruction instruction = new GotoInstruction(GotoInstruction.GOTO_32, new Label(0, 0x1_0000));
        assertEquals(3, instruction.unitSize(), "GOTO_32 should be 3 code units");

        Format format = GotoInstruction.CODEC.unmap(instruction, unmapContext(instruction));
        assertInstanceOf(Format00opAAAA32.class, format);
    }

    @Test
    void invalidOpcodeThrowsForUnitSize() {
        GotoInstruction instruction = new GotoInstruction(99, new Label());
        assertThrows(IllegalArgumentException.class, instruction::unitSize);
    }

    private static InstructionContext<DexMapBuilder> unmapContext(GotoInstruction instruction) {
        return new InstructionContext<>(
                List.of(instruction),
                List.of(0),
                new DexMapBuilder(),
                new HashMap<>(),
                null,
                null,
                null
        );
    }

}
