package me.darknet.dex.convert;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.List;

/**
 * @param classes
 * 		Resulting class bytecode, mapped by internal class name.
 * @param errors
 * 		Any errors that occurred during conversion, mapped by internal class name.
 * @param diagnostics
 * 		Non-fatal conversion observations, mapped by internal class name.
 */
public record ConversionResult(@NotNull Map<String, byte[]> classes,
                               @NotNull Map<String, Throwable> errors,
                               @NotNull Map<String, List<ConversionDiagnostic>> diagnostics) {
	public ConversionResult(@NotNull Map<String, byte[]> classes,
	                        @NotNull Map<String, Throwable> errors) {
		this(classes, errors, Map.of());
	}

	public ConversionResult {
		classes = Map.copyOf(classes);
		errors = Map.copyOf(errors);
		diagnostics = diagnostics.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
				Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
	}
}
