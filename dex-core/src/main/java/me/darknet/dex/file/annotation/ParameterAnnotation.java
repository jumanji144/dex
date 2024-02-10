package me.darknet.dex.file.annotation;

import me.darknet.dex.codecs.DexCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.file.items.AnnotationSetRefList;
import me.darknet.dex.file.items.MethodItem;
import me.darknet.dex.io.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record ParameterAnnotation(MethodItem method, AnnotationSetRefList annotations) {

    public static final DexCodec<ParameterAnnotation> CODEC = new DexCodec<>() {
        @Override
        public ParameterAnnotation read(Input input, DexMapAccess context) throws IOException {
            MethodItem field = context.methods().get(input.readInt());
            int offset = (int) input.readUnsignedInt();
            AnnotationSetRefList annotations = AnnotationSetRefList.CODEC.read(input.slice(offset), context);
            return new ParameterAnnotation(field, annotations);
        }

        @Override
        public void write(ParameterAnnotation value, Output output, WriteContext context) throws IOException {
            output.writeInt(context.index().methods().indexOf(value.method()));
            AnnotationSetRefList.CODEC.write(value.annotations(), output, context);
        }
    };
    
}
