package me.darknet.dex.tree.type;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class TypeParser {
    private final CharSequence cs;
    private int index;

    public TypeParser(CharSequence cs) {
        this.cs = cs;
    }

    @Nullable
    public Type read() {
        CharSequence cs = this.cs;
        int index = this.index;
        int length = cs.length();
        if (index < length) {
            char c = cs.charAt(index++);
            if (c == '[') {
                this.index = index;
                Type type = read();
                if (!(type instanceof ClassType ct)) {
                    this.index = index - 1;
                    throw new IllegalStateException("Expected ClassType");
                }
                return new ArrayType(ct);
            }
            if (c == 'L') {
                int start = index;
                for (; index < length; index++) {
                    if (cs.charAt(index) == ';') {
                        InstanceType instanceType = Types.instanceTypeFromInternalName(cs.subSequence(start, index).toString());
                        this.index = index + 1;
                        return instanceType;
                    }
                }
                this.index = start - 1;
                throw new IllegalStateException("Expected InstanceType");
            }
            this.index = index;
            switch (c) {
                case 'V':
                    return Types.VOID;
                case 'J':
                    return Types.LONG;
                case 'D':
                    return Types.DOUBLE;
                case 'I':
                    return Types.INT;
                case 'F':
                    return Types.FLOAT;
                case 'C':
                    return Types.CHAR;
                case 'S':
                    return Types.SHORT;
                case 'B':
                    return Types.BYTE;
                case 'Z':
                    return Types.BOOLEAN;
            }
            if (c != '(') {
                throw new IllegalStateException("Expected MethodType start");
            }
            int start = index;
            List<ClassType> parameterTypes = new ArrayList<>();
            while (cs.charAt(index) != ')') {
                Type parameter = read();
                if (!(parameter instanceof ClassType ct)) {
                    this.index = start - 1;
                    throw new IllegalStateException("Expected ClassType");
                }
                parameterTypes.add(ct);
                index = this.index;
            }
            this.index += 1;
            Type returnType = read();
            if (!(returnType instanceof ClassType ct)) {
                this.index = start - 1;
                throw new IllegalStateException("Expected ClassType");
            }
            return new MethodType(ct, parameterTypes);
        }
        return null;
    }

    public Type required() {
        Type t = read();
        if (t == null) {
            throw new IllegalStateException("Expected type");
        }
        return t;
    }

    public ClassType requireClassType() {
        Type t = read();
        if (!(t instanceof ClassType ct)) {
            throw new IllegalStateException("Expected ClassType");
        }
        return ct;
    }
}