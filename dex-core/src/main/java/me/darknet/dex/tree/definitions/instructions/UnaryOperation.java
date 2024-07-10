package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.tree.type.PrimitiveKind;
import me.darknet.dex.tree.type.PrimitiveType;

public interface UnaryOperation  {

    int NEG_INT = 0;
    int NOT_INT = 1;
    int NEG_LONG = 2;
    int NOT_LONG = 3;
    int NEG_FLOAT = 4;
    int NEG_DOUBLE = 5;
    int INT_TO_LONG = 6;
    int INT_TO_FLOAT = 7;
    int INT_TO_DOUBLE = 8;
    int LONG_TO_INT = 9;
    int LONG_TO_FLOAT = 10;
    int LONG_TO_DOUBLE = 11;
    int FLOAT_TO_INT = 12;
    int FLOAT_TO_LONG = 13;
    int FLOAT_TO_DOUBLE = 14;
    int DOUBLE_TO_INT = 15;
    int DOUBLE_TO_LONG = 16;
    int DOUBLE_TO_FLOAT = 17;
    int INT_TO_BYTE = 18;
    int INT_TO_CHAR = 19;
    int INT_TO_SHORT = 20;

    int NEG = 0;
    int NOT = 1;
    int INT_TO = 2;
    int LONG_TO = 3;
    int FLOAT_TO = 4;
    int DOUBLE_TO = 5;

    static int operation(int kind, PrimitiveType type) {
        return switch (kind) {
            case NEG -> switch (type.kind()) {
                case PrimitiveKind.T_INT -> NEG_INT;
                case PrimitiveKind.T_LONG -> NEG_LONG;
                case PrimitiveKind.T_FLOAT -> NEG_FLOAT;
                case PrimitiveKind.T_DOUBLE -> NEG_DOUBLE;
                default -> throw new IllegalArgumentException("Invalid type for NEG: " + type);
            };
            case NOT -> switch (type.kind()) {
                case PrimitiveKind.T_INT -> NOT_INT;
                case PrimitiveKind.T_LONG -> NOT_LONG;
                default -> throw new IllegalArgumentException("Invalid type for NOT: " + type);
            };
            case INT_TO -> switch (type.kind()) {
                case PrimitiveKind.T_LONG -> INT_TO_LONG;
                case PrimitiveKind.T_FLOAT -> INT_TO_FLOAT;
                case PrimitiveKind.T_DOUBLE -> INT_TO_DOUBLE;
                case PrimitiveKind.T_BYTE -> INT_TO_BYTE;
                case PrimitiveKind.T_CHAR -> INT_TO_CHAR;
                case PrimitiveKind.T_SHORT -> INT_TO_SHORT;
                default -> throw new IllegalArgumentException("Invalid type for INT_TO: " + type);
            };
            case LONG_TO -> switch (type.kind()) {
                case PrimitiveKind.T_INT -> LONG_TO_INT;
                case PrimitiveKind.T_FLOAT -> LONG_TO_FLOAT;
                case PrimitiveKind.T_DOUBLE -> LONG_TO_DOUBLE;
                default -> throw new IllegalArgumentException("Invalid type for LONG_TO: " + type);
            };
            case FLOAT_TO -> switch (type.kind()) {
                case PrimitiveKind.T_INT -> FLOAT_TO_INT;
                case PrimitiveKind.T_LONG -> FLOAT_TO_LONG;
                case PrimitiveKind.T_DOUBLE -> FLOAT_TO_DOUBLE;
                default -> throw new IllegalArgumentException("Invalid type for FLOAT_TO: " + type);
            };
            case DOUBLE_TO -> switch (type.kind()) {
                case PrimitiveKind.T_INT -> DOUBLE_TO_INT;
                case PrimitiveKind.T_LONG -> DOUBLE_TO_LONG;
                case PrimitiveKind.T_FLOAT -> DOUBLE_TO_FLOAT;
                default -> throw new IllegalArgumentException("Invalid type for DOUBLE_TO: " + type);
            };
            default -> throw new IllegalArgumentException("Unknown kind: " + kind);
        };
    }
}
