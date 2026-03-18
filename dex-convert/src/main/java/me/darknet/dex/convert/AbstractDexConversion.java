package me.darknet.dex.convert;

import me.darknet.dex.convert.factory.WriterFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * An abstract implementation of {@link DexConversion} that provides a default implementation for the writer factory.
 */
public abstract class AbstractDexConversion implements DexConversion {
	private WriterFactory factory;

	@Override
	public @NotNull WriterFactory getWriterFactory() {
		return Objects.requireNonNullElse(factory, WriterFactory.DEFAULT);
	}

	@Override
	public void setWriterFactory(@NotNull WriterFactory factory) {
		this.factory = factory;
	}
}
