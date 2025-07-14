package me.darknet.dex.file.annotation;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.file.value.Value;
import me.darknet.dex.codecs.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record EncodedAnnotation(@NotNull TypeItem type, @NotNull List<AnnotationElement> elements) {

    public static final ContextCodec<EncodedAnnotation, DexMapAccess, WriteContext> CODEC = new ContextCodec<>() {

        @Override
        public EncodedAnnotation read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            TypeItem type = context.types().get(input.readULeb128());
            int size = input.readULeb128();
            List<AnnotationElement> elements = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                StringItem name = context.strings().get(input.readULeb128());
                Value value = Value.CODEC.read(input, context);
                elements.add(new AnnotationElement(name, value));
            }
            return new EncodedAnnotation(type, elements);
        }

        @Override
        public void write(EncodedAnnotation value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeULeb128(context.index().types().indexOf(value.type()));
            output.writeULeb128(value.elements().size());
            for (AnnotationElement element : value.elements()) {
                output.writeULeb128(context.index().strings().indexOf(element.name()));
                Value.CODEC.write(element.value(), output, context);
            }
        }
    };

}
