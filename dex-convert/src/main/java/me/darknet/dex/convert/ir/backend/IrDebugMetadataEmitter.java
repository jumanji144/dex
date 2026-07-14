package me.darknet.dex.convert.ir.backend;

import me.darknet.dex.convert.ir.IrMethod;
import org.jetbrains.annotations.NotNull;

/** Source-position and local-variable metadata boundary. */
public interface IrDebugMetadataEmitter<T> {
	void emit(@NotNull IrMethod method, @NotNull T output);
}
