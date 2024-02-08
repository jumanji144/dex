package me.darknet.dex.file.annotation;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.file.items.FieldItem;
import me.darknet.dex.io.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record FieldAnnotation(FieldItem field, AnnotationSetItem annotations) {

    public static final ContextCodec<FieldAnnotation, DexMapAccess> CODEC = new ContextCodec<>() {
        @Override
        public FieldAnnotation read(Input input, DexMapAccess context) throws IOException {
            FieldItem field = context.fields().get(input.readInt());
            int offset = (int) input.readUnsignedInt();
            AnnotationSetItem annotations = AnnotationSetItem.CODEC.read(input.slice(offset), context);
            return new FieldAnnotation(field, annotations);
        }

        @Override
        public void write(FieldAnnotation value, Output output, DexMapAccess context) throws IOException {
            output.writeInt(context.fields().indexOf(value.field()));
            AnnotationSetItem.CODEC.write(value.annotations(), output, context);
        }
    };

}
