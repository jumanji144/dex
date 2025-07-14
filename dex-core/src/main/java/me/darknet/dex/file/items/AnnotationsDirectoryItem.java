package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.annotation.FieldAnnotation;
import me.darknet.dex.file.annotation.MethodAnnotation;
import me.darknet.dex.file.annotation.ParameterAnnotation;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record AnnotationsDirectoryItem(@NotNull AnnotationSetItem classAnnotations,
                                       @NotNull List<FieldAnnotation> fieldAnnotations,
                                       @NotNull List<MethodAnnotation> methodAnnotations,
                                       @NotNull List<ParameterAnnotation> parameterAnnotations) implements Item {

    public static final ItemCodec<AnnotationsDirectoryItem> CODEC = new ItemCodec<>() {

        @Override
        public AnnotationsDirectoryItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int classAnnotationsOffset = (int) input.readUnsignedInt();
            int fieldAnnotationsSize = (int) input.readUnsignedInt();
            int methodAnnotationsSize = (int) input.readUnsignedInt();
            int parameterAnnotationsSize = (int) input.readUnsignedInt();
            AnnotationSetItem classAnnotations = classAnnotationsOffset == 0 ? new AnnotationSetItem(new ArrayList<>(0)) : AnnotationSetItem.CODEC.read(input.slice(classAnnotationsOffset), context);
            List<FieldAnnotation> fieldAnnotations = new ArrayList<>(fieldAnnotationsSize);
            for (int i = 0; i < fieldAnnotationsSize; i++) {
                fieldAnnotations.add(FieldAnnotation.CODEC.read(input, context));
            }
            List<MethodAnnotation> methodAnnotations = new ArrayList<>(methodAnnotationsSize);
            for (int i = 0; i < methodAnnotationsSize; i++) {
                methodAnnotations.add(MethodAnnotation.CODEC.read(input, context));
            }
            List<ParameterAnnotation> parameterAnnotations = new ArrayList<>(parameterAnnotationsSize);
            for (int i = 0; i < parameterAnnotationsSize; i++) {
                parameterAnnotations.add(ParameterAnnotation.CODEC.read(input, context));
            }
            return new AnnotationsDirectoryItem(classAnnotations, fieldAnnotations, methodAnnotations, parameterAnnotations);
        }

        @Override
        public void write0(AnnotationsDirectoryItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeInt(value.classAnnotations().isEmpty() ? 0 : context.offset(value.classAnnotations()));
            output.writeInt(value.fieldAnnotations().size());
            output.writeInt(value.methodAnnotations().size());
            output.writeInt(value.parameterAnnotations().size());
            for (FieldAnnotation fieldAnnotation : value.fieldAnnotations()) {
                FieldAnnotation.CODEC.write(fieldAnnotation, output, context);
            }
            for (MethodAnnotation methodAnnotation : value.methodAnnotations()) {
                MethodAnnotation.CODEC.write(methodAnnotation, output, context);
            }
            for (ParameterAnnotation parameterAnnotation : value.parameterAnnotations()) {
                ParameterAnnotation.CODEC.write(parameterAnnotation, output, context);
            }
        }

        @Override
        public int alignment() {
            return 4;
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(classAnnotations, fieldAnnotations, methodAnnotations, parameterAnnotations);
    }
}
