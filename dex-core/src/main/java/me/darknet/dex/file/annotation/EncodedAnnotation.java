package me.darknet.dex.file.annotation;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.file.value.Value;
import me.darknet.dex.io.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record EncodedAnnotation(TypeItem type, List<AnnotationElement> elements) {

    public static final ContextCodec<EncodedAnnotation, DexMapAccess> CODEC = new ContextCodec<>() {

        @Override
        public EncodedAnnotation read(Input input, DexMapAccess context) throws IOException {
            TypeItem type = context.types().get(input.readUnsignedShort());
            int size = input.readInt();
            List<AnnotationElement> elements = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                StringItem name = context.strings().get(input.readUnsignedShort());
                Value value = Value.CODEC.read(input, context);
                elements.add(new AnnotationElement(name, value));
            }
            return new EncodedAnnotation(type, elements);
        }

        @Override
        public void write(EncodedAnnotation value, Output output, DexMapAccess context) throws IOException {
            output.writeShort(context.types().indexOf(value.type));
            output.writeInt(value.elements.size());
            for (AnnotationElement element : value.elements) {
                output.writeShort(context.strings().indexOf(element.name()));
                Value.CODEC.write(element.value(), output, context);
            }
        }
    };

}
