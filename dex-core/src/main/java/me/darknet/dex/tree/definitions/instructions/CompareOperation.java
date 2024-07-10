package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Opcodes;

public interface CompareOperation {

    int LT_FLOAT = Opcodes.CMPL_FLOAT;
    int GT_FLOAT = Opcodes.CMPG_FLOAT;
    int LT_DOUBLE = Opcodes.CMPL_DOUBLE;
    int GT_DOUBLE = Opcodes.CMPG_DOUBLE;
    int CMP_LONG = Opcodes.CMP_LONG;

}
