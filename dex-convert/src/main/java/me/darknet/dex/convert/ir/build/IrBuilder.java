package me.darknet.dex.convert.ir.build;

import me.darknet.dex.convert.ir.DexIrException;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.analysis.IrTypeAnalysis;
import me.darknet.dex.convert.ir.analysis.IrTypeResolver;
import me.darknet.dex.convert.ConversionDiagnostic;
import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.convert.ir.value.IrUnknown;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class IrBuilder {
	private final MethodMember method;
	private final IrGraphBuilder graphBuilder;
	private final IrBlockBuilder blockBuilder;
	private final boolean pruneGraph;
	private final List<ConversionDiagnostic> diagnostics = new ArrayList<>();
	private final Set<IrUnknown> reportedUnknowns = Collections.newSetFromMap(new IdentityHashMap<>());
	private boolean tainted;
	private IrTypeResolver typeResolver = IrTypeResolver.EMPTY;

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

	public void setTypeResolver(@NotNull IrTypeResolver typeResolver) {
		this.typeResolver = typeResolver;
	}

	public @NotNull IrMethod build() {
		Code code = method.getCode();
		if (code == null)
			throw new DexIrException("lift", method, "Method has no code");

		IrGraph graph = pruneGraph ?
				graphBuilder.buildPrunedGraph() :
				graphBuilder.buildGraph();
		blockBuilder.buildBlocks(graph);
		blockBuilder.reportUnknowns(graph.blocks());
		IrMethod preliminary = new IrMethod(method, code.getRegisters(), graph.blocks(), graph.entry(), graph.exceptionRegions(),
				tainted, List.copyOf(diagnostics));
		IrTypeAnalysis.Result typeResult = IrTypeAnalysis.analyze(preliminary, typeResolver);
		typeResult.diagnostics().forEach(this::report);
		return new IrMethod(method, code.getRegisters(), graph.blocks(), graph.entry(), graph.exceptionRegions(),
				tainted, List.copyOf(diagnostics), typeResult.flowFacts());
	}

	void report(@NotNull ConversionDiagnostic diagnostic) {
		diagnostics.add(diagnostic);
		if (diagnostic.severity() != ConversionDiagnostic.Severity.INFO) tainted = true;
	}

	void reportUnknown(@NotNull IrUnknown unknown, int register) {
		if (!reportedUnknowns.add(unknown)) return;
		String className = method.getOwner() == null ? "<unknown>" : ConversionSupport.asmOwner(method.getOwner());
		report(new ConversionDiagnostic(className, method.toString(), unknown.dexOffset(),
				ConversionDiagnostic.Severity.WARNING, ConversionDiagnostic.Kind.UNKNOWN_VALUE,
				"Undefined or invalid DEX register " + register + " materialized as a typed unknown", null));
	}

	void reportInvalid(@NotNull ConversionDiagnostic.Kind kind, int offset, @NotNull String message) {
		String className = method.getOwner() == null ? "<unknown>" : ConversionSupport.asmOwner(method.getOwner());
		report(new ConversionDiagnostic(className, method.toString(), offset,
				ConversionDiagnostic.Severity.ERROR, kind, message, null));
	}
}
