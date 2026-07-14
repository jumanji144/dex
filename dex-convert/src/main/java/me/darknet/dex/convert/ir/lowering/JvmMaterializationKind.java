package me.darknet.dex.convert.ir.lowering;

/** Internal materialization choices for one canonical JVM value/use. */
enum JvmMaterializationKind {
	CONSTANT,
	TYPED_FALLBACK,
	LOCAL,
	SINGLE_USE_INLINE,
	RECEIVER_CHAIN,
	STACK_CARRY,
	SPECIAL_CHAIN
}
