package me.darknet.dex.convert.ir.build;

import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class IrBuildingUtilsTypeTest {
    @Test
    void adaptsConstantsWithoutMutatingSharedValues() {
        IrConstant original = new IrConstant(1, Types.INT, 0, true);
        IrValue adapted = IrBuildingUtils.adaptType(original, Types.FLOAT);

        assertNotSame(original, adapted);
        assertEquals(Types.INT, original.type());
        assertEquals(Types.FLOAT, adapted.type());
    }
}
