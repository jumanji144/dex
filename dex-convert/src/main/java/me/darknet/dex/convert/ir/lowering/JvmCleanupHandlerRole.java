package me.darknet.dex.convert.ir.lowering;

/**
 * Structural roles used while composing nested cleanup handlers.  Roles are
 * inferred from CFG/SSA facts; they are never selected from class or method
 * names.
 */
enum JvmCleanupHandlerRole {
	RESOURCE_CLOSE,
	SUPPRESSED_RETHROW,
	OUTER_FAILURE,
	FINALLY_CLEANUP
}
