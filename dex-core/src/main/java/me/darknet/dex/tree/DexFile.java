package me.darknet.dex.tree;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationProcessing;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record DexFile(int version, @NotNull List<ClassDefinition> definitions, byte[] link) {

    public static final TreeCodec<DexFile, DexHeader> CODEC = new TreeCodec<>() {
        @Override
        public @NotNull DexFile map(@NotNull DexHeader input, @NotNull DexMap context) {
            List<ClassDefinition> definitions = new ArrayList<>();
            for (ClassDefItem item : input.map().classes())
                definitions.add(ClassDefinition.CODEC.map(item, input.map()));
            processAttributes(definitions);
            return new DexFile(input.version(), definitions, input.link());
        }

        @Override
        public @NotNull DexHeader unmap(@NotNull DexFile output, @NotNull DexMapBuilder context) {
            DexMapBuilder builder = new DexMapBuilder();
            for (ClassDefinition definition : output.definitions) {
                ClassDefItem item = ClassDefinition.CODEC.unmap(definition, builder);
                builder.classes().add(item);
            }
            return new DexHeader(output.version(), output.link(), builder.build());
        }

        private void processAttributes(@NotNull List<ClassDefinition> definitions) {
            Map<String, ClassDefinition> definitionMap = new HashMap<>(definitions.size());
            for (ClassDefinition definition : definitions)
                definitionMap.put(definition.getType().internalName(), definition);
            for (ClassDefinition definition : definitions)
                processAttributes(definitionMap, definition);
        }

        private void processAttributes(@NotNull Map<String, ClassDefinition> definitionMap,
                                       @NotNull ClassDefinition definition) {
            List<Annotation> original = definition.getAnnotations();
            for (Annotation annotation : new ArrayList<>(original)) {
                if (annotation.visibility() == Annotation.VISIBILITY_SYSTEM) {
                    if (AnnotationProcessing.processAttribute(definitionMap, definition, annotation.annotation())) {
                        original.remove(annotation);
                    }
                }
            }
        }
    };

}
