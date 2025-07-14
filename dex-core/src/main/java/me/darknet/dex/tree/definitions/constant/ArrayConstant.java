package me.darknet.dex.tree.definitions.constant;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ArrayConstant(@NotNull List<Constant> constants) implements Constant {
}
