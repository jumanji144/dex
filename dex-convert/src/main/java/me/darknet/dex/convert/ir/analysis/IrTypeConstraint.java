package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.convert.ir.value.IrType;
import org.jetbrains.annotations.NotNull;

/** A contextual type requirement attached to one IR input. */
public record IrTypeConstraint(@NotNull IrType expected, @NotNull String role) {}
