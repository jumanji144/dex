package me.darknet.dex.tree.definitions.instructions;

public interface Operation {

    int GET = 0;
    int GET_WIDE = 1;
    int GET_OBJECT = 2;
    int GET_BOOLEAN = 3;
    int GET_BYTE = 4;
    int GET_CHAR = 5;
    int GET_SHORT = 6;
    int PUT = 7;
    int PUT_WIDE = 8;
    int PUT_OBJECT = 9;
    int PUT_BOOLEAN = 10;
    int PUT_BYTE = 11;
    int PUT_CHAR = 12;
    int PUT_SHORT = 13;

    static String name(int kind) {
        return switch (kind) {
            case GET -> "get";
            case GET_WIDE -> "get-wide";
            case GET_OBJECT -> "get-object";
            case GET_BOOLEAN -> "get-boolean";
            case GET_BYTE -> "get-byte";
            case GET_CHAR -> "get-char";
            case GET_SHORT -> "get-short";
            case PUT -> "put";
            case PUT_WIDE -> "put-wide";
            case PUT_OBJECT -> "put-object";
            case PUT_BOOLEAN -> "put-boolean";
            case PUT_BYTE -> "put-byte";
            case PUT_CHAR -> "put-char";
            case PUT_SHORT -> "put-short";
            default -> throw new IllegalArgumentException("Unknown kind: " + kind);
        };
    }

}
