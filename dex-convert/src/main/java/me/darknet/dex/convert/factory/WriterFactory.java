package me.darknet.dex.convert.factory;

import me.darknet.dex.tree.definitions.ClassDefinition;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;

/**
 * Factory for creating {@link ClassWriter} instances for class definitions.
 */
public interface WriterFactory {
	/**
	 * Default factory that creates a new writer with no flags for each class.
	 * <p>
	 * This instance is intended for use cases where the result is not intended to be loaded by a class loader.
	 */
	WriterFactory DEFAULT = cls -> new ClassWriter(0);

	/**
	 * @param cls
	 * 		The class definition for which to create a writer.
	 *
	 * @return A new writer instance for the given class definition.
	 */
	@NotNull ClassWriter newWriter(@NotNull ClassDefinition cls);
}
