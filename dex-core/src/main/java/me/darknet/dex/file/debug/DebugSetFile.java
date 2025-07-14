package me.darknet.dex.file.debug;

import me.darknet.dex.file.items.StringItem;
import org.jetbrains.annotations.NotNull;

public record DebugSetFile(@NotNull StringItem name) implements DebugInstruction {
}
