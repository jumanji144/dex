package me.darknet.dex.file.annotation;

import me.darknet.dex.codecs.DexCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.file.items.MethodItem;
import me.darknet.dex.io.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record MethodAnnotation(MethodItem method, AnnotationSetItem annotations) {

    public static final DexCodec<MethodAnnotation> CODEC = new DexCodec<>() {
        @Override
        public MethodAnnotation read(Input input, DexMapAccess context) throws IOException {
            MethodItem field = context.methods().get(input.readInt());
            int offset = (int) input.readUnsignedInt();
            AnnotationSetItem annotations = AnnotationSetItem.CODEC.read(input.slice(offset), context);
            return new MethodAnnotation(field, annotations);
        }

        @Override
        public void write(MethodAnnotation value, Output output, WriteContext context) throws IOException {
            output.writeInt(context.index().methods().indexOf(value.method()));
            AnnotationSetItem.CODEC.write(value.annotations(), output, context);
        }
    };
    
}
