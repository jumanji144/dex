package me.darknet.dex.file;

import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.file.items.MethodItem;

public record EncodedMethod(MethodItem method, int access, CodeItem code) {
}
