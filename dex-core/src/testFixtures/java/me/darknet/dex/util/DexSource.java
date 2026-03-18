package me.darknet.dex.util;

import org.junit.jupiter.api.function.ThrowingSupplier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public record DexSource(String name, ThrowingSupplier<InputStream> source) {
	public static DexSource from(Path path) {
		return new DexSource(path.getParent().getFileName() + "/" + path.getFileName().toString(),
				() -> Files.newInputStream(path));
	}

	@Override
	public String toString() {
		return name;
	}
}
