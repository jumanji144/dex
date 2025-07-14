package me.darknet.dex.tree.definitions.annotation;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.items.AnnotationItem;
import me.darknet.dex.tree.codec.TreeCodec;
import org.jetbrains.annotations.NotNull;

public record Annotation(byte visibility, AnnotationPart annotation) {

    public static final int VISIBILITY_BUILD = 0x00;
    public static final int VISIBILITY_RUNTIME = 0x01;
    public static final int VISIBILITY_SYSTEM = 0x02;

    public static final TreeCodec<Annotation, AnnotationItem> CODEC = new TreeCodec<>() {
        @Override
        public @NotNull Annotation map(@NotNull AnnotationItem input, @NotNull DexMap context) {
            return new Annotation(input.visibility(), AnnotationPart.CODEC.map(input.annotation(), context));
        }

        @Override
        public @NotNull AnnotationItem unmap(@NotNull Annotation output, @NotNull DexMapBuilder context) {
            return new AnnotationItem(output.visibility(), AnnotationPart.CODEC.unmap(output.annotation(), context));
        }
    };

}
