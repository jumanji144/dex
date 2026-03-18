package me.darknet.dex.convert.ir;

import me.darknet.dex.tree.definitions.MethodMember;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @param source
 * 		Input method that this IR was generated from.
 * @param registerCount
 * 		Number of registers used by the method.
 * @param blocks
 * 		List of basic blocks in the method, in no particular order.
 * @param entry
 * 		The entry block of the method, which is the first block to be executed when the method is called.
 * @param tryCatches
 * 		List of try-catch blocks in the method.
 */
public record IrMethod(@NotNull MethodMember source,
                       int registerCount,
                       @NotNull List<IrBlock> blocks,
                       @NotNull IrBlock entry,
                       @NotNull List<IrTryCatch> tryCatches) {}
