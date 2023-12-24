package me.darknet.dex.file.debug;

import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.items.TypeItem;

public record DebugStartLocalExtended(int registerNum, StringItem name, TypeItem type, StringItem signature)
        implements DebugInstruction {
}
