package me.darknet.dex.convert.ir;

import me.darknet.dex.convert.ConversionDiagnostic;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Result of lowering one IR method, including recoverable backend diagnostics. */
public record IrLoweringResult(boolean tainted,
                               @NotNull List<ConversionDiagnostic> diagnostics) {
	public IrLoweringResult {
		diagnostics = List.copyOf(diagnostics);
	}
}
