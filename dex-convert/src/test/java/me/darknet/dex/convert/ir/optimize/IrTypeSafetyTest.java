package me.darknet.dex.convert.ir.optimize;

import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrOpKind;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class IrTypeSafetyTest {
    @Test
    void unknownInputsAreNotFoldableOrStackOnly() {
        IrUnknown unknown = new IrUnknown(1, Types.INT, IrTypeKind.INT, null, 7);
        IrOp op = new IrOp(2, Types.INT, IrOpKind.BINARY,
                List.of(unknown, new IrConstant(3, Types.INT, 1, false)), null);

        assertFalse(op.pure());
        unknown.stackOnly(true);
        assertFalse(unknown.stackOnly());
    }
}
