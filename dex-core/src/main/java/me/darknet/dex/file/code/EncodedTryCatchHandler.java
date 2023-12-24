package me.darknet.dex.file.code;

import java.util.List;

public record EncodedTryCatchHandler(List<EncodedTypeAddrPair> handlers, int catchAllAddr) {
}
