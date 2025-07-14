package me.darknet.dex.file.code;

import org.jetbrains.annotations.NotNull;

public record TryItem(int startAddr, int count, @NotNull EncodedTryCatchHandler handler) {

}
