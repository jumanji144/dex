package me.darknet.dex.tree.definitions;

public interface AccessFlags {

    int ACC_PUBLIC = 0x0001;
    int ACC_PRIVATE = 0x0002;
    int ACC_PROTECTED = 0x0004;
    int ACC_STATIC = 0x0008;
    int ACC_FINAL = 0x0010;
    int ACC_SYNCHRONIZED = 0x0020;
    int ACC_VOLATILE = 0x0040;
    int ACC_BRIDGE = 0x0040;
    int ACC_TRANSIENT = 0x0080;
    int ACC_VARARGS = 0x0080;
    int ACC_NATIVE = 0x0100;
    int ACC_INTERFACE = 0x0200;
    int ACC_ABSTRACT = 0x0400;
    int ACC_STRICT = 0x0800;
    int ACC_SYNTHETIC = 0x1000;
    int ACC_ANNOTATION = 0x2000;
    int ACC_ENUM = 0x4000;
    int ACC_CONSTRUCTOR = 0x10000;
    int ACC_DECLARED_SYNCHRONIZED = 0x20000;

}
