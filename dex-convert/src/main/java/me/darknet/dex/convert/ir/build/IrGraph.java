package me.darknet.dex.convert.ir.build;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrTryCatch;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public record IrGraph(@NotNull List<IrBlock> blocks,
                      @NotNull IrBlock entry,
                      @NotNull Map<Integer, IrBlock> blockByOffset,
                      @NotNull List<IrTryCatch> tryCatches) {}
