package me.darknet.dex.tree.definitions.constant;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.items.FieldItem;
import me.darknet.dex.file.items.MethodHandleItem;
import me.darknet.dex.file.items.MethodItem;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

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

    public static final TreeCodec<Handle, MethodHandleItem> CODEC = new TreeCodec<>() {
        @Override
        public @NotNull Handle map(@NotNull MethodHandleItem input, @NotNull DexMap context) {
            return switch (input.type()) {
                case Handle.KIND_STATIC_PUT, Handle.KIND_STATIC_GET,
                     Handle.KIND_INSTANCE_PUT, Handle.KIND_INSTANCE_GET -> {
                    FieldItem field = context.fields().get(input.index());
                    yield new Handle(input.type(), Types.instanceType(field.owner()), field.name().string(),
                            Types.classType(field.type()));
                }
                case Handle.KIND_INVOKE_STATIC, Handle.KIND_INVOKE_INSTANCE,
                     Handle.KIND_INVOKE_CONSTRUCTOR, Handle.KIND_INVOKE_DIRECT,
                     Handle.KIND_INVOKE_INTERFACE -> {
                    MethodItem method = context.methods().get(input.index());
                    yield new Handle(input.type(), Types.instanceType(method.owner()), method.name().string(),
                            Types.methodType(method.proto()));
                }
                default -> throw new IllegalStateException("Unexpected value: " + input.type());
            };
        }

        @Override
        public @NotNull MethodHandleItem unmap(@NotNull Handle output, @NotNull DexMapBuilder context) {
            int index = switch (output.kind) {
                case Handle.KIND_STATIC_PUT, Handle.KIND_STATIC_GET,
                     Handle.KIND_INSTANCE_PUT, Handle.KIND_INSTANCE_GET -> context.addField(output.owner, output.name, output.type);
                case Handle.KIND_INVOKE_STATIC, Handle.KIND_INVOKE_INSTANCE,
                     Handle.KIND_INVOKE_CONSTRUCTOR, Handle.KIND_INVOKE_DIRECT,
                     Handle.KIND_INVOKE_INTERFACE -> context.addMethod(output.owner, output.name,
                        (MethodType) output.type);
                default -> throw new IllegalStateException("Unexpected value: " + output.kind);
            };
            return new MethodHandleItem(output.kind, index);
        }
    };

}
