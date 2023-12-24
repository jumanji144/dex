package me.darknet.dex.file.code;

import me.darknet.dex.file.items.TypeItem;

public record EncodedTypeAddrPair(TypeItem exceptionType, int addr) {
}
