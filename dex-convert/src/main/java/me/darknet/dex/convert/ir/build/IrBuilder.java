package me.darknet.dex.convert.ir.build;

import me.darknet.dex.convert.ir.DexIrException;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import org.jetbrains.annotations.NotNull;

public class IrBuilder {
	private final MethodMember method;
	private final IrGraphBuilder graphBuilder;
	private final IrBlockBuilder blockBuilder;
	private final boolean pruneGraph;

	public IrBuilder(@NotNull MethodMember method) {
		this(method, true);
	}

	public IrBuilder(@NotNull MethodMember method, boolean pruneGraph) {
		this.method = method;
		this.pruneGraph = pruneGraph;

		graphBuilder = new IrGraphBuilder(this);
		blockBuilder = new IrBlockBuilder(this);
	}

	public @NotNull MethodMember getInputMethod() {
		return method;
	}

	public @NotNull IrMethod build() {
		Code code = method.getCode();
		if (code == null)
			throw new DexIrException("lift", method, "Method has no code");

		IrGraph graph = pruneGraph ?
				graphBuilder.buildPrunedGraph() :
				graphBuilder.buildGraph();
		blockBuilder.buildBlocks(graph);
		return new IrMethod(method, code.getRegisters(), graph.blocks(), graph.entry(), graph.tryCatches());
	}
}
