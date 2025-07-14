package me.darknet.dex.file.annotation;

import me.darknet.dex.codecs.DexCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.file.items.MethodItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public record MethodAnnotation(@NotNull MethodItem method, @NotNull AnnotationSetItem annotations) {

    public static final DexCodec<MethodAnnotation> CODEC = new DexCodec<>() {
        @Override
        public MethodAnnotation read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            MethodItem field = context.methods().get(input.readInt());
            int offset = (int) input.readUnsignedInt();
            AnnotationSetItem annotations = AnnotationSetItem.CODEC.read(input.slice(offset), context);
            return new MethodAnnotation(field, annotations);
        }

        @Override
        public void write(MethodAnnotation value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeInt(context.index().methods().indexOf(value.method()));
            output.writeInt(context.offset(value.annotations()));
        }
    };
    
}
