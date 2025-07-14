package me.darknet.dex.tree.definitions;

import me.darknet.dex.tree.type.Type;

public sealed interface Typed<T extends Type> permits ClassDefinition, Member {

    T getType();

}
