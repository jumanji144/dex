package me.darknet.dex.tree.definitions;

import me.darknet.dex.tree.definitions.annotation.Annotation;

import java.util.List;

public sealed interface Annotated permits ClassDefinition, Member {

    List<Annotation> annotations();

}
