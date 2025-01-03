package me.darknet.dex.tree.definitions.code;

import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.type.InstanceType;
import org.jetbrains.annotations.Nullable;

public record Handler(Label handler, @Nullable InstanceType exceptionType) {

    public boolean isCatchAll() {
        return exceptionType == null;
    }

}
