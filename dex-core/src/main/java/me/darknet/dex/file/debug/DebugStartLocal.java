package me.darknet.dex.file.debug;

import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.items.TypeItem;
import org.jetbrains.annotations.NotNull;

public record DebugStartLocal(int registerNum, @NotNull StringItem name, @NotNull TypeItem type) implements DebugInstruction {
}
