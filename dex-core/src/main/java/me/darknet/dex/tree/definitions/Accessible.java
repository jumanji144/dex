package me.darknet.dex.tree.definitions;

public sealed interface Accessible permits ClassDefinition, Member {

    int access();

}
