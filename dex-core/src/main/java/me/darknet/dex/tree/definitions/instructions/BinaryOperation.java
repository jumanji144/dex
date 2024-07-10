package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.tree.type.PrimitiveKind;
import me.darknet.dex.tree.type.PrimitiveType;
import me.darknet.dex.tree.type.Types;

public interface BinaryOperation {

    int ADD = 0;
    int SUB = 1;
    int RSUB = 1;
    int MUL = 2;
    int DIV = 3;
    int REM = 4;
    int AND = 5;
    int OR = 6;
    int XOR = 7;
    int SHL = 8;
    int SHR = 9;
    int USHR = 10;

    static int operation(int kind, PrimitiveType type) {
        return switch (type.kind()) {
            case PrimitiveKind.T_INT -> kind;
            case PrimitiveKind.T_LONG -> kind + USHR + 1;
            case PrimitiveKind.T_FLOAT -> kind + (USHR + 1) * 2;
            case PrimitiveKind.T_DOUBLE -> kind + (USHR + 1) * 2 + (REM + 1);
            default -> throw new IllegalArgumentException("Invalid type for BinaryOperation: " + type);
        };
    }

}
