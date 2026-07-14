package me.darknet.dex.convert.factory;

import me.darknet.dex.tree.definitions.ClassDefinition;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassWriter;

/**
 * Factory for creating {@link ClassWriter} instances for class definitions.
 */
public interface WriterFactory {
	/**
	 * Default factory for loadable output. Frames and maxima are computed after
	 * the IR lowering has emitted the class.
	 */
	WriterFactory DEFAULT = cls -> new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

	/**
	 * @param cls
	 * 		The class definition for which to create a writer.
	 *
	 * @return A new writer instance for the given class definition.
	 */
	@NotNull ClassWriter newWriter(@NotNull ClassDefinition cls);
}
