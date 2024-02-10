package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.annotation.EncodedAnnotation;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public record AnnotationItem(byte visibility, EncodedAnnotation annotation) implements Item {

    public static final ItemCodec<AnnotationItem> CODEC = new ItemCodec<>() {
        @Override
        public AnnotationItem read0(Input input, DexMapAccess context) throws IOException {
            byte visibility = input.readByte();
            EncodedAnnotation annotation = EncodedAnnotation.CODEC.read(input, context);
            return new AnnotationItem(visibility, annotation);
        }

        @Override
        public void write0(AnnotationItem value, Output output, WriteContext context) throws IOException {
            output.writeByte(value.visibility());
            EncodedAnnotation.CODEC.write(value.annotation(), output, context);
        }
    };

}
