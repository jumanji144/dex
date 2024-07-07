package me.darknet.dex.tree.definitions.constant;

import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Type;

public record Handle(int kind, InstanceType owner, String name, Type type) {

    public static final int KIND_STATIC_PUT = 0x00;
    public static final int KIND_STATIC_GET = 0x01;
    public static final int KIND_INSTANCE_PUT = 0x02;
    public static final int KIND_INSTANCE_GET = 0x03;
    public static final int KIND_INVOKE_STATIC = 0x04;
    public static final int KIND_INVOKE_INSTANCE = 0x05;
    public static final int KIND_INVOKE_CONSTRUCTOR = 0x06;
    public static final int KIND_INVOKE_DIRECT = 0x07;
    public static final int KIND_INVOKE_INTERFACE = 0x08;

}
