package me.darknet.dex.tree.codec;

import org.jetbrains.annotations.NotNull;

public interface ContextMappingCodec<I, O, CI, CO> {

    @NotNull O map(@NotNull I input, @NotNull CI context);

    @NotNull I unmap(@NotNull O output, @NotNull CO context);

}
