package me.darknet.dex.tree.type;

import org.jetbrains.annotations.NotNull;

public sealed interface ReferenceType extends ClassType permits InstanceType, ArrayType {

    @NotNull String internalName();

    default @NotNull String externalName() {
        return Types.externalName(internalName());
    }

}
