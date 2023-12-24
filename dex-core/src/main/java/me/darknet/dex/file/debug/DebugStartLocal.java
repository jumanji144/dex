package me.darknet.dex.file.debug;

import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.items.TypeItem;

public record DebugStartLocal(int registerNum, StringItem name, TypeItem type) implements DebugInstruction {
}
