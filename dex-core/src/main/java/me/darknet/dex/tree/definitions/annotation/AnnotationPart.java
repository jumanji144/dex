package me.darknet.dex.tree.definitions.annotation;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.annotation.AnnotationElement;
import me.darknet.dex.file.annotation.EncodedAnnotation;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.definitions.constant.AnnotationConstant;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;

import java.util.HashMap;
import java.util.Map;

public record AnnotationPart(InstanceType type, Map<String, Constant> elements) {

    public static final TreeCodec<AnnotationPart, EncodedAnnotation> CODEC = new TreeCodec<>() {
        @Override
        public AnnotationPart map(EncodedAnnotation input, DexMap context) {
            InstanceType type = Types.instanceType(input.type());
            Map<String, Constant> entries = new HashMap<>();
            for (AnnotationElement element : input.elements()) {
                entries.put(element.name().string(), Constant.CODEC.map(element.value(), context));
            }
            return new AnnotationPart(type, entries);
        }

        @Override
        public EncodedAnnotation unmap(AnnotationPart output, DexMapBuilder context) {
            return null;
        }
    };

}
