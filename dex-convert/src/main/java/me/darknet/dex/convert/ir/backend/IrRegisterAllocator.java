package me.darknet.dex.convert.ir.backend;

import me.darknet.dex.convert.ir.IrMethod;
import org.jetbrains.annotations.NotNull;

/** Target register/local allocation boundary. */
public interface IrRegisterAllocator {
	void allocate(@NotNull IrMethod method);
}
