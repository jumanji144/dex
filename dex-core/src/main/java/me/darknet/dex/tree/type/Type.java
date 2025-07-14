package me.darknet.dex.tree.type;

import org.jetbrains.annotations.NotNull;

public sealed interface Type permits ClassType, MethodType {

    @NotNull String descriptor();

}
