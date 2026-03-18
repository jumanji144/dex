package me.darknet.dex.convert.ir.build;

import me.darknet.dex.convert.TryCatchSupport;
import me.darknet.dex.convert.ir.DexInstructionNode;
import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrTryCatch;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveResultInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.SparseSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static me.darknet.dex.convert.ir.build.IrBuildingUtils.canThrow;

public class IrGraphBuilder {
	private final Code code;

	public IrGraphBuilder(@NotNull IrBuilder builder) {
		code = Objects.requireNonNull(builder.getInputMethod().getCode(), "Method has no code");
	}

	public @NotNull IrGraph buildPrunedGraph() {
		return pruneUnreachable(buildGraph());
	}

	public @NotNull IrGraph buildGraph() {
		List<TryCatch> tryCatches = TryCatchSupport.effectiveTryCatches(code);
		List<DexInstructionNode> raw = new ArrayList<>();
		int fallbackOffset = 0;
		int codeEnd = 0;
		for (Instruction instruction : code.getInstructions()) {
			if (instruction instanceof Label label) {
				fallbackOffset = label.position();
				continue;
			}
			int offset = instructionOffset(instruction, fallbackOffset);
			raw.add(new DexInstructionNode(offset, instruction));
			codeEnd = Math.max(codeEnd, offset + instruction.byteSize());
			if (code.offsetOf(instruction) == null) {
				fallbackOffset = offset + instruction.byteSize();
			}
		}
		raw.sort(Comparator.comparingInt(DexInstructionNode::offset));
		if (raw.isEmpty()) {
			IrBlock entry = new IrBlock(0, 0);
			return new IrGraph(List.of(entry), entry, Map.of(0, entry), List.of());
		}

		Set<Integer> leaders = new HashSet<>();
		leaders.add(raw.getFirst().offset());
		for (int i = 0; i < raw.size(); i++) {
			DexInstructionNode node = raw.get(i);
			switch (node.instruction()) {
				case BranchInstruction branchInstruction -> {
					leaders.add(branchInstruction.label().position());
					if (i + 1 < raw.size()) leaders.add(raw.get(i + 1).offset());
				}
				case BranchZeroInstruction branchZeroInstruction -> {
					leaders.add(branchZeroInstruction.label().position());
					if (i + 1 < raw.size()) leaders.add(raw.get(i + 1).offset());
				}
				case GotoInstruction gotoInstruction -> {
					leaders.add(gotoInstruction.jump().position());
					if (i + 1 < raw.size()) leaders.add(raw.get(i + 1).offset());
				}
				case PackedSwitchInstruction packedSwitchInstruction -> {
					for (Label target : packedSwitchInstruction.targets()) leaders.add(target.position());
					if (i + 1 < raw.size()) leaders.add(raw.get(i + 1).offset());
				}
				case SparseSwitchInstruction sparseSwitchInstruction -> {
					for (Label target : sparseSwitchInstruction.targets().values()) leaders.add(target.position());
					if (i + 1 < raw.size()) leaders.add(raw.get(i + 1).offset());
				}
				case ReturnInstruction ignored -> {
					if (i + 1 < raw.size()) leaders.add(raw.get(i + 1).offset());
				}
				case ThrowInstruction ignored -> {
					if (i + 1 < raw.size()) leaders.add(raw.get(i + 1).offset());
				}
				case MoveExceptionInstruction ignored -> leaders.add(node.offset());
				default -> {
				}
			}
			if (canThrow(node.instruction()) && hasRelevantHandler(tryCatches, node)) {
				if (i > 0) {
					leaders.add(node.offset());
				}
			}
			if (i + 1 < raw.size() && canThrow(node.instruction()) && hasRelevantHandler(tryCatches, node)) {
				int splitIndex = i + 1;
				if (raw.get(splitIndex).instruction() instanceof MoveResultInstruction) {
					splitIndex++;
				}
				if (splitIndex < raw.size()) {
					leaders.add(raw.get(splitIndex).offset());
				}
			}
		}
		for (TryCatch tryCatch : tryCatches) {
			leaders.add(tryCatch.begin().position());
			if (tryCatch.end().position() < codeEnd) leaders.add(tryCatch.end().position());
			for (Handler handler : tryCatch.handlers()) leaders.add(handler.handler().position());
		}

		List<Integer> orderedLeaders = new ArrayList<>(leaders);
		Collections.sort(orderedLeaders);
		List<IrBlock> blocks = new ArrayList<>(orderedLeaders.size());
		Map<Integer, IrBlock> blockByOffset = new HashMap<>();
		for (int i = 0; i < orderedLeaders.size(); i++) {
			IrBlock block = new IrBlock(i, orderedLeaders.get(i));
			blocks.add(block);
			blockByOffset.put(block.startOffset(), block);
		}

		int blockIndex = 0;
		for (DexInstructionNode node : raw) {
			while (blockIndex + 1 < blocks.size() && node.offset() >= blocks.get(blockIndex + 1).startOffset()) {
				blockIndex++;
			}
			blocks.get(blockIndex).dexInstructions().add(node);
		}

		for (int i = 0; i < blocks.size(); i++) {
			IrBlock block = blocks.get(i);
			IrBlock fallthrough = i + 1 < blocks.size() ? blocks.get(i + 1) : null;
			if (block.dexInstructions().isEmpty()) {
				if (fallthrough != null) block.addSuccessor(fallthrough, false);
				continue;
			}
			Instruction terminal = block.dexInstructions().getLast().instruction();
			switch (terminal) {
				case BranchInstruction branchInstruction -> {
					block.addSuccessor(requireBlock(blockByOffset, branchInstruction.label().position()), false);
					if (fallthrough != null) block.addSuccessor(fallthrough, false);
				}
				case BranchZeroInstruction branchZeroInstruction -> {
					block.addSuccessor(requireBlock(blockByOffset, branchZeroInstruction.label().position()), false);
					if (fallthrough != null) block.addSuccessor(fallthrough, false);
				}
				case GotoInstruction gotoInstruction ->
						block.addSuccessor(requireBlock(blockByOffset, gotoInstruction.jump().position()), false);
				case PackedSwitchInstruction packedSwitchInstruction -> {
					for (Label target : packedSwitchInstruction.targets()) {
						block.addSuccessor(requireBlock(blockByOffset, target.position()), false);
					}
					if (fallthrough != null) block.addSuccessor(fallthrough, false);
				}
				case SparseSwitchInstruction sparseSwitchInstruction -> {
					for (Label target : sparseSwitchInstruction.targets().values()) {
						block.addSuccessor(requireBlock(blockByOffset, target.position()), false);
					}
					if (fallthrough != null) block.addSuccessor(fallthrough, false);
				}
				case ReturnInstruction ignored -> {
				}
				case ThrowInstruction ignored -> {
				}
				default -> {
					if (fallthrough != null) block.addSuccessor(fallthrough, false);
				}
			}
			if (block.dexInstructions().stream().anyMatch(node -> canThrow(node.instruction()) && hasRelevantHandler(tryCatches, node))) {
				attachHandlers(tryCatches, block, blockByOffset);
			}
		}

		List<IrTryCatch> irTryCatches = new ArrayList<>();
		for (TryCatch tryCatch : tryCatches) {
			for (Handler handler : tryCatch.handlers()) {
				irTryCatches.add(new IrTryCatch(tryCatch.begin().position(), tryCatch.end().position(),
						requireBlock(blockByOffset, handler.handler().position()), handler));
			}
		}

		return new IrGraph(blocks, blocks.getFirst(), blockByOffset, irTryCatches);
	}

	private @NotNull IrGraph pruneUnreachable(@NotNull IrGraph graph) {
		Set<IrBlock> reachable = new HashSet<>();
		collectReachable(graph.entry(), reachable);
		if (reachable.size() == graph.blocks().size())
			return graph;

		List<IrBlock> liveBlocks = graph.blocks().stream()
				.filter(reachable::contains)
				.toList();
		Map<IrBlock, IrBlock> remapped = new HashMap<>();
		List<IrBlock> blocks = new ArrayList<>(liveBlocks.size());
		Map<Integer, IrBlock> blockByOffset = new HashMap<>();
		for (int i = 0; i < liveBlocks.size(); i++) {
			IrBlock block = new IrBlock(i, liveBlocks.get(i).startOffset());
			block.dexInstructions().addAll(liveBlocks.get(i).dexInstructions());
			remapped.put(liveBlocks.get(i), block);
			blocks.add(block);
			blockByOffset.put(block.startOffset(), block);
		}

		for (IrBlock oldBlock : liveBlocks) {
			IrBlock block = remapped.get(oldBlock);
			for (IrBlock successor : oldBlock.successors()) {
				if (reachable.contains(successor))
					block.addSuccessor(remapped.get(successor), false);
			}
			for (IrBlock successor : oldBlock.exceptionalSuccessors()) {
				if (reachable.contains(successor))
					block.addSuccessor(remapped.get(successor), true);
			}
		}

		List<IrTryCatch> tryCatches = new ArrayList<>();
		for (IrTryCatch tryCatch : graph.tryCatches()) {
			if (!reachable.contains(tryCatch.handlerBlock()))
				continue;
			if (!blockByOffset.containsKey(tryCatch.startOffset()))
				continue;
			tryCatches.add(new IrTryCatch(tryCatch.startOffset(), tryCatch.endOffset(),
					remapped.get(tryCatch.handlerBlock()), tryCatch.handler()));
		}

		return new IrGraph(blocks, remapped.get(graph.entry()), blockByOffset, tryCatches);
	}

	private void collectReachable(@NotNull IrBlock block, @NotNull Set<IrBlock> reachable) {
		if (!reachable.add(block))
			return;
		for (IrBlock successor : block.successors())
			collectReachable(successor, reachable);
		for (IrBlock successor : block.exceptionalSuccessors())
			collectReachable(successor, reachable);
	}

	private boolean hasRelevantHandler(@NotNull List<TryCatch> tryCatches, @NotNull DexInstructionNode node) {
		if (!isProtectedByTryCatch(tryCatches, node.offset())) return false;
		for (TryCatch tryCatch : tryCatches) {
			if (node.offset() < tryCatch.begin().position() || node.offset() >= tryCatch.end().position()) continue;
			for (Handler handler : tryCatch.handlers()) {
				if (IrBuildingUtils.canThrowToHandler(node.instruction(), handler)) return true;
			}
		}
		return false;
	}

	private boolean isProtectedByTryCatch(@NotNull List<TryCatch> tryCatches, int offset) {
		for (TryCatch tryCatch : tryCatches) {
			if (offset >= tryCatch.begin().position() && offset < tryCatch.end().position()) {
				return true;
			}
		}
		return false;
	}

	private void attachHandlers(@NotNull List<TryCatch> tryCatches, @NotNull IrBlock throwingBlock,
	                            @NotNull Map<Integer, IrBlock> blockByOffset) {
		for (DexInstructionNode node : throwingBlock.dexInstructions()) {
			if (!canThrow(node.instruction())) continue;
			for (TryCatch tryCatch : tryCatches) {
				if (node.offset() < tryCatch.begin().position() || node.offset() >= tryCatch.end().position()) continue;
				for (Handler handler : tryCatch.handlers()) {
					if (!IrBuildingUtils.canThrowToHandler(node.instruction(), handler))
						continue;

					// TODO: We should be recording exception edge information too for better analysis later on.
					//  Currently, we just add a normal successor edge to the handler block, but this means we
					//  lose information about which exceptions are caught and where they are caught.
					throwingBlock.addSuccessor(requireBlock(blockByOffset, handler.handler().position()), true);
				}
			}
		}
	}

	private static @NotNull IrBlock requireBlock(@NotNull Map<Integer, IrBlock> blockByOffset, int offset) {
		IrBlock block = blockByOffset.get(offset);
		if (block == null) throw new IllegalStateException("Missing block at offset " + offset);
		return block;
	}

	private int instructionOffset(@NotNull Instruction instruction, int fallbackOffset) {
		Integer offset = code.offsetOf(instruction);
		return offset != null ? offset : fallbackOffset;
	}
}
