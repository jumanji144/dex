package me.darknet.dex.convert.ir.backend;

import me.darknet.dex.convert.ir.IrMethod;
import org.jetbrains.annotations.NotNull;

/** Branch and code-offset layout boundary. DEX implementations are two-pass. */
public interface IrBranchLayout {
	void layout(@NotNull IrMethod method);
}
