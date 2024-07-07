package me.darknet.dex.tree.type;

import me.darknet.dex.file.items.ProtoItem;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.file.items.TypeListItem;

import java.util.ArrayList;
import java.util.List;

public class Types {
    public static PrimitiveType BYTE = new PrimitiveType("B", PrimitiveKind.T_BYTE, "byte");
    public static PrimitiveType CHAR = new PrimitiveType("C", PrimitiveKind.T_CHAR, "char");
    public static PrimitiveType DOUBLE = new PrimitiveType("D", PrimitiveKind.T_DOUBLE, "double");
    public static PrimitiveType FLOAT = new PrimitiveType("F", PrimitiveKind.T_FLOAT, "float");
    public static PrimitiveType INT = new PrimitiveType("I", PrimitiveKind.T_INT, "int");
    public static PrimitiveType LONG = new PrimitiveType("J", PrimitiveKind.T_LONG, "long");
    public static PrimitiveType SHORT = new PrimitiveType("S", PrimitiveKind.T_SHORT, "short");
    public static PrimitiveType BOOLEAN = new PrimitiveType("Z", PrimitiveKind.T_BOOLEAN, "boolean");
    public static PrimitiveType VOID = new PrimitiveType("V", PrimitiveKind.T_VOID, "void");

    public static InstanceType OBJECT = instanceType(Object.class);

    public static InstanceType instanceTypeFromDescriptor(String descriptor) {
        if (descriptor.charAt(0) != 'L' || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("Not an instance type descriptor: " + descriptor);
        }
        return new InstanceType(descriptor);
    }

    public static MethodType methodTypeFromDescriptor(String descriptor) {
        TypeParser parser = new TypeParser(descriptor);
        Type parsed = parser.read();
        if (!(parsed instanceof MethodType)) {
            throw new IllegalArgumentException("Expected MethodType");
        }
        return (MethodType) parsed;
    }

    public static ArrayType arrayTypeFromDescriptor(String descriptor) {
        TypeParser parser = new TypeParser(descriptor);
        Type parsed = parser.read();
        if (!(parsed instanceof ArrayType)) {
            throw new IllegalArgumentException("Expected ArrayType");
        }
        return (ArrayType) parsed;
    }

    public static PrimitiveType primitiveTypeFromDescriptor(String descriptor) {
        char c = descriptor.charAt(0);
        return switch (c) {
            case 'B' -> BYTE;
            case 'C' -> CHAR;
            case 'D' -> DOUBLE;
            case 'F' -> FLOAT;
            case 'I' -> INT;
            case 'J' -> LONG;
            case 'S' -> SHORT;
            case 'Z' -> BOOLEAN;
            case 'V' -> VOID;
            default -> throw new IllegalArgumentException("Not a primitive type descriptor: " + descriptor);
        };
    }

    public static InstanceType instanceTypeFromInternalName(String internalName) {
        return new InstanceType('L' + internalName + ';');
    }

    public static InstanceType instanceType(Class<?> clazz) {
        if (clazz.isPrimitive() || clazz.isArray()) {
            throw new IllegalArgumentException("Cannot create instance type for primitive or array class: " + clazz);
        }
        return instanceTypeFromInternalName(internalName(clazz.getName()));
    }

    public static Type typeFromDescriptor(String descriptor) {
        char c = descriptor.charAt(0);
        return switch (c) {
            case 'V' -> VOID;
            case 'J' -> LONG;
            case 'D' -> DOUBLE;
            case 'I' -> INT;
            case 'F' -> FLOAT;
            case 'C' -> CHAR;
            case 'S' -> SHORT;
            case 'B' -> BYTE;
            case 'Z' -> BOOLEAN;
            case '(' -> methodTypeFromDescriptor(descriptor);
            case 'L' -> instanceTypeFromDescriptor(descriptor);
            case '[' -> arrayTypeFromDescriptor(descriptor);
            default -> throw new IllegalArgumentException("Not a valid type descriptor: " + descriptor);
        };
    }

    public static ClassType classType(TypeItem item) {
        TypeParser parser = new TypeParser(item.descriptor().string());
        return parser.requireClassType();
    }

    public static List<ClassType> classTypes(TypeListItem item) {
        List<ClassType> types = new ArrayList<>(item.types().size());
        for (TypeItem type : item.types()) {
            types.add(classType(type));
        }
        return types;
    }

    public static InstanceType instanceType(TypeItem item) {
        return instanceTypeFromDescriptor(item.descriptor().string());
    }

    public static List<InstanceType> instanceTypes(TypeListItem item) {
        List<InstanceType> types = new ArrayList<>(item.types().size());
        for (TypeItem type : item.types()) {
            types.add(instanceType(type));
        }
        return types;
    }

    public static MethodType methodType(ProtoItem item) {
        ClassType returnType = classType(item.returnType());
        List<ClassType> parameterTypes = classTypes(item.parameters());
        return new MethodType(returnType, parameterTypes);
    }

    public static String shortyDescriptor(ClassType type) {
        char c = type.descriptor().charAt(0);
        if (c == '[')
            return "L";
        return Character.toString(c);
    }

    public static String shortyDescriptor(MethodType type) {
        StringBuilder builder = new StringBuilder();
        builder.append(Types.shortyDescriptor(type.returnType()));
        for (ClassType parameter : type.parameterTypes()) {
            builder.append(Types.shortyDescriptor(parameter));
        }
        return builder.toString();
    }

    public static String internalName(String externalName) {
        return externalName.replace('.', '/');
    }

    public static String externalName(String internalName) {
        return internalName.replace('/', '.');
    }
}
