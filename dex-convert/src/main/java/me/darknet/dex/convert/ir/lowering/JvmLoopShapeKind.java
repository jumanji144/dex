package me.darknet.dex.convert.ir.lowering;

/** JVM layout shapes that can be recovered from a proven DEX loop. */
enum JvmLoopShapeKind {
	SHORT_CIRCUIT,
	COUNTED,
	ARRAY_INDEX
}
