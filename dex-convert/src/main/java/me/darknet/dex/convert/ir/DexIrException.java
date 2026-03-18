package me.darknet.dex.convert.ir;

import me.darknet.dex.tree.definitions.MethodMember;
import org.jetbrains.annotations.NotNull;

public final class DexIrException extends RuntimeException {
	public DexIrException(@NotNull String phase, @NotNull MethodMember method, @NotNull String message) {
		super(format(phase, method, message), null);
	}

	private static String format(@NotNull String phase, @NotNull MethodMember method, @NotNull String message) {
		return phase + " failed for " + method.getOwner().internalName() + "." + method.getName() + method.getType() + ": " + message;
	}
}
