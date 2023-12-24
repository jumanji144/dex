package me.darknet.dex.file.debug;

import me.darknet.dex.file.items.StringItem;

public record DebugSetFile(StringItem name) implements DebugInstruction {
}
