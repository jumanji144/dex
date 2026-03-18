package me.darknet.dex.convert.ir;

import me.darknet.dex.tree.definitions.instructions.Instruction;
import org.jetbrains.annotations.NotNull;

/**
 * @param offset
 * 		Original instruction offset in the method code.
 * @param instruction
 * 		The instruction itself, which may be modified during optimization.
 */
public record DexInstructionNode(int offset, @NotNull Instruction instruction) {}