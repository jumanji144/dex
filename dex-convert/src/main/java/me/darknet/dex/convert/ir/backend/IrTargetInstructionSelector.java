package me.darknet.dex.convert.ir.backend;

import me.darknet.dex.convert.ir.statement.IrStmt;
import org.jetbrains.annotations.NotNull;

/** Target-specific instruction selection hook for future backends. */
public interface IrTargetInstructionSelector<T> {
	@NotNull T select(@NotNull IrStmt statement);
}
