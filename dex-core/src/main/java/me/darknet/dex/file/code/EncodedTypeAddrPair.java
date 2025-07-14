package me.darknet.dex.file.code;

import me.darknet.dex.file.items.TypeItem;
import org.jetbrains.annotations.NotNull;

public record EncodedTypeAddrPair(@NotNull TypeItem exceptionType, int addr) {
}
