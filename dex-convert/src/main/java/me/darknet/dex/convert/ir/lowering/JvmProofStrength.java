package me.darknet.dex.convert.ir.lowering;

/** Strength of the proof used to enable an optional JVM optimization. */
enum JvmProofStrength {
	NONE,
	GUARDED,
	AGGRESSIVE
}
