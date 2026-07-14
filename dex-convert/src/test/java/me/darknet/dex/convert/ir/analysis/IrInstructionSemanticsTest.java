package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrEffectKind;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrOpKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.tree.type.Types;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrInstructionSemanticsTest {
    @Test
    void describesArithmeticAndReferenceOperations() {
        IrOp arithmetic = new IrOp(1, Types.INT, IrOpKind.BINARY,
                List.of(new IrConstant(2, Types.INT, 1, false), new IrConstant(3, Types.INT, 2, false)), null);
        IrInstructionSemantics arithmeticSemantics = arithmetic.semantics();
        assertEquals("BINARY", arithmeticSemantics.loweringId());
        assertEquals(2, arithmeticSemantics.inputs().size());
        assertEquals(IrInstructionSemantics.Effect.PURE, arithmeticSemantics.effect());

        IrOp receiver = new IrOp(4, Types.OBJECT, IrOpKind.INSTANCE_GET,
                List.of(new IrConstant(5, Types.OBJECT, null, true)), null);
        assertEquals(IrTypeKind.REFERENCE, receiver.semantics().inputs().getFirst().expected().kind());
        assertEquals(IrInstructionSemantics.Effect.OBSERVABLE, receiver.semantics().effect());
    }

    @Test
    void describesEffectsAndThrowMasks() {
        IrEffect monitor = new IrEffect(IrEffectKind.MONITOR,
                List.of(new IrConstant(1, Types.OBJECT, null, true)), null);
        assertEquals("MONITOR", monitor.semantics().constructionId());
        assertTrue(monitor.semantics().inputs().getFirst().expected().kind().isReference());
    }

    @Test
    void givesConstructionOnlyDexInstructionsStableContracts() {
        IrInstructionSemantics semantics = IrInstructionSemantics.forConstruction(new ConstInstruction(0, 1, 7));
        assertEquals("dex.ConstInstruction", semantics.constructionId());
        assertEquals(0, semantics.inputs().size());
        assertTrue(semantics.complete());
    }
}
