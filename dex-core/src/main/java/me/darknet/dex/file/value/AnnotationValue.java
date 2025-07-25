package me.darknet.dex.file.value;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.annotation.EncodedAnnotation;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record AnnotationValue(@NotNull EncodedAnnotation annotation) implements Value {

    public static final ValueCodec<AnnotationValue> CODEC = new ValueCodec<>() {
        @Override
        public AnnotationValue read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            return new AnnotationValue(EncodedAnnotation.CODEC.read(input, context));
        }

        @Override
        public void write(AnnotationValue value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeByte(value.type()); // 0 << 5 | 0x1c
            EncodedAnnotation.CODEC.write(value.annotation, output, context);
        }

        @Override
        public int size() {
            return 0;
        }
    };

    @Override
    public int type() {
        return 0x1d;
    }

}
