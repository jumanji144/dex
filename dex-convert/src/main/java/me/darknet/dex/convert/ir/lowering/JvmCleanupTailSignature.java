package me.darknet.dex.convert.ir.lowering;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Canonical identity for a cleanup suffix.  The signature deliberately has no
 * register or local identity in it: those are edge arguments, not semantics.
 */
record JvmCleanupTailSignature(
		@NotNull List<String> effects,
		@NotNull String terminal,
		@NotNull List<String> exceptionProfile,
		@NotNull List<String> valueRoles) {
	JvmCleanupTailSignature {
		effects = List.copyOf(effects);
		exceptionProfile = List.copyOf(exceptionProfile);
		valueRoles = List.copyOf(valueRoles);
	}
}
