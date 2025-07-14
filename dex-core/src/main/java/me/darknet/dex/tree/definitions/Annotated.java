package me.darknet.dex.tree.definitions;

import me.darknet.dex.tree.definitions.annotation.Annotation;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public sealed interface Annotated permits ClassDefinition, Member {

    @NotNull List<Annotation> getAnnotations();

}
