package me.darknet.dex.convert;

import me.darknet.dex.convert.factory.WriterFactory;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Outline of Dex to Java class conversion pipelines.
 */
public interface DexConversion {
	/**
	 * @return Factory for creating class writers.
	 */
	@NotNull WriterFactory getWriterFactory();

	/**
	 * @param factory
	 * 		Factory for creating class writers.
	 */
	void setWriterFactory(@NotNull WriterFactory factory);

	/**
	 * Convert all classes in the given dex file to Java bytecode.
	 *
	 * @param dex
	 * 		The Dex file to convert
	 *
	 * @return A mapping of class internal names to their corresponding Java class bytecode,
	 * and a mapping of any classes that failed to convert to the exceptions that were thrown during their conversion.
	 */
	@NotNull ConversionResult toClasses(@NotNull DexFile dex);

	/**
	 * Convert a single class to Java bytecode.
	 *
	 * @param cls
	 * 		The dex class to convert to Java bytecode
	 *
	 * @return The Java class bytecode corresponding to the given dex class.
	 * {@code null} if the class could not be converted.
	 */
	byte @Nullable [] toJavaClass(@NotNull ClassDefinition cls);
}
