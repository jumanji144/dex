package me.darknet.dex.tree.type;

public sealed interface Type permits ClassType, MethodType {

    String descriptor();

}
