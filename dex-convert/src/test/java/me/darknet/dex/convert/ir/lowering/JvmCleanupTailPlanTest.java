package me.darknet.dex.convert.ir.lowering;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JvmCleanupTailPlanTest {
	@Test
	void signaturesUseSemanticCategoriesInsteadOfLocalIdentity() {
		JvmCleanupTailSignature first = new JvmCleanupTailSignature(
				java.util.List.of("invoke:close:OBSERVABLE"), "RETURN:REFERENCE",
				java.util.List.of("cleanup:catch-all"), java.util.List.of("receiver:REFERENCE:MAYBE_NULL"));
		JvmCleanupTailSignature second = new JvmCleanupTailSignature(
				java.util.List.of("invoke:close:OBSERVABLE"), "RETURN:REFERENCE",
				java.util.List.of("cleanup:catch-all"), java.util.List.of("receiver:REFERENCE:MAYBE_NULL"));

		assertEquals(first, second);
		assertThrows(UnsupportedOperationException.class, () -> first.effects().add("register-42"));
	}

	@Test
	void signatureRejectsDifferentEffectOrExceptionProfiles() {
		JvmCleanupTailSignature close = new JvmCleanupTailSignature(
				java.util.List.of("invoke:close:OBSERVABLE"), "RETURN:VOID",
				java.util.List.of("cleanup:IOException"), java.util.List.of());
		JvmCleanupTailSignature remove = new JvmCleanupTailSignature(
				java.util.List.of("invoke:remove:OBSERVABLE"), "RETURN:VOID",
				java.util.List.of("cleanup:IOException"), java.util.List.of());
		JvmCleanupTailSignature catchAll = new JvmCleanupTailSignature(
				java.util.List.of("invoke:close:OBSERVABLE"), "RETURN:VOID",
				java.util.List.of("cleanup:Throwable"), java.util.List.of());

		assertNotEquals(close, remove);
		assertNotEquals(close, catchAll);
	}

	@Test
	void constructorCopiesMutableInputs() {
		ArrayList<String> effects = new ArrayList<>();
		effects.add("invoke:remove");
		JvmCleanupTailSignature signature = new JvmCleanupTailSignature(effects, "THROW:REFERENCE", java.util.List.of(), java.util.List.of());
		effects.add("invoke:close");

		assertEquals(1, signature.effects().size());
		assertEquals("invoke:remove", signature.effects().getFirst());
	}
}
