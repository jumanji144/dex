package me.darknet.dex.file.annotation;

import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.value.Value;
import org.jetbrains.annotations.NotNull;

public record AnnotationElement(@NotNull StringItem name, @NotNull Value value) {
}
