package me.darknet.dex.file.code;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record TryItem(int startAddr, int count, EncodedTryCatchHandler handler) {

}
