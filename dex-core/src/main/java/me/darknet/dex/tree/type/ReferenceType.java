package me.darknet.dex.tree.type;

public sealed interface ReferenceType extends ClassType permits InstanceType, ArrayType {

    String internalName();

    default String externalName() {
        return Types.externalName(internalName());
    }

}
