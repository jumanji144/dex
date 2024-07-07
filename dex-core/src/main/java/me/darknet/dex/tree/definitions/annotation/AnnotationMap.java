package me.darknet.dex.tree.definitions.annotation;

import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.file.items.FieldItem;
import me.darknet.dex.file.items.MethodItem;

import java.util.List;
import java.util.Map;

public record AnnotationMap(Map<FieldItem, AnnotationSetItem> fieldAnnotations,
                            Map<MethodItem, AnnotationSetItem> methodAnnotations,
                            Map<MethodItem, List<AnnotationSetItem>> parameterAnnotations) {
}
