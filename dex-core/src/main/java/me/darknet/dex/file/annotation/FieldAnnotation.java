package me.darknet.dex.file.annotation;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.file.items.FieldItem;
import me.darknet.dex.codecs.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record FieldAnnotation(@NotNull FieldItem field, @NotNull AnnotationSetItem annotations) {

    public static final ContextCodec<FieldAnnotation, DexMapAccess, WriteContext> CODEC = new ContextCodec<>() {
        @Override
        public FieldAnnotation read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            FieldItem field = context.fields().get(input.readInt());
            int offset = (int) input.readUnsignedInt();
            AnnotationSetItem annotations = AnnotationSetItem.CODEC.read(input.slice(offset), context);
            return new FieldAnnotation(field, annotations);
        }

        @Override
        public void write(FieldAnnotation value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeInt(context.index().fields().indexOf(value.field()));
            output.writeInt(context.offset(value.annotations()));
        }
    };

}
