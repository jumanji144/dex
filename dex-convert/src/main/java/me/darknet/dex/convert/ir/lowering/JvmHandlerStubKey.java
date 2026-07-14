package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.IrBlock;
import org.jetbrains.annotations.NotNull;

/** Identity key for an exception edge's JVM handler-entry stub. */
record JvmHandlerStubKey(@NotNull IrBlock source, @NotNull IrBlock target) {}
