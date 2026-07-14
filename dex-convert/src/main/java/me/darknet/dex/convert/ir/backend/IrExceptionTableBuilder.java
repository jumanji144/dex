package me.darknet.dex.convert.ir.backend;

import me.darknet.dex.convert.ir.IrExceptionEdge;
import me.darknet.dex.convert.ir.IrMethod;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Target exception-table construction boundary. */
public interface IrExceptionTableBuilder {
	@NotNull List<IrExceptionEdge> build(@NotNull IrMethod method);
}
