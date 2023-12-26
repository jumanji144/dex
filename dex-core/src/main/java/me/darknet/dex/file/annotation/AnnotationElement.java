package me.darknet.dex.file.annotation;

import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.value.Value;

public record AnnotationElement(StringItem name, Value value) {
}
