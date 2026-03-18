package me.darknet.dex.util;

import org.junit.jupiter.api.function.ThrowingSupplier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public record DexAndJarSource(String name, ThrowingSupplier<InputStream> dexSource,
                              ThrowingSupplier<InputStream> javaSource) {
	public static DexAndJarSource from(Path path) {
		String pathName = path.getParent().getFileName() + "/" + path.getFileName().toString();
		return new DexAndJarSource(pathName,
				() -> Files.newInputStream(path),
				() -> Files.newInputStream(path.getParent().resolve("classes.jar")));
	}

	@Override
	public String toString() {
		return name;
	}
}
