package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.instructions.Opcodes;

import java.lang.reflect.Field;

public class OpcodeNames {

    private static final String[] NAMES = new String[256];

    public static String name(int opcode) {
        return NAMES[opcode];
    }

    static {
        for (Field declaredField : Opcodes.class.getDeclaredFields()) {
            try {
                int value = declaredField.getInt(null);
                if (value >= 0 && value < NAMES.length) {
                    NAMES[value] = declaredField.getName().replace('_', '-').toLowerCase();
                }
            } catch (IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

}
