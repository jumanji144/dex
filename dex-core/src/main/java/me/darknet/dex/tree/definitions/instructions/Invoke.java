package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Opcodes;

public interface Invoke {

    int VIRTUAL = Opcodes.INVOKE_VIRTUAL;
    int DIRECT = Opcodes.INVOKE_DIRECT;
    int STATIC = Opcodes.INVOKE_STATIC;
    int INTERFACE = Opcodes.INVOKE_INTERFACE;
    int SUPER = Opcodes.INVOKE_SUPER;

}
