package me.darknet.dex.convert;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A non-fatal or fatal observation made while converting one DEX method. */
public record ConversionDiagnostic(
		@NotNull String className,
		@NotNull String method,
		int dexOffset,
		@NotNull Severity severity,
		@NotNull Kind kind,
		@NotNull String message,
		@Nullable Throwable cause) {
	public enum Severity { INFO, WARNING, ERROR }

	public enum Kind {
		UNKNOWN_VALUE,
		INVALID_REGISTER,
		INVALID_WIDE_REGISTER,
		SEMANTICS,
		TYPE_INFERENCE,
		FALLBACK,
		UNSAFE_OPTIMIZATION,
		VERIFIER
	}
}
