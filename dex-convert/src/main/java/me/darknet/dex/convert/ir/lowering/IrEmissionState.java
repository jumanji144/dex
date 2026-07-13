package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrOp;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Mutable bookkeeping for one JVM method emission.
 */
final class IrEmissionState {
	private final Set<IrOp> emittedOps = new HashSet<>();
	private final Set<IrEffect> emittedEffects = Collections.newSetFromMap(new IdentityHashMap<>());

	@NotNull Set<IrOp> emittedOps() {
		return emittedOps;
	}

	@NotNull Set<IrEffect> emittedEffects() {
		return emittedEffects;
	}
}

