package me.darknet.dex.convert;

import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.convert.util.Decompile;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.util.TestUtils;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Smoke test for the converters. Operates on a sample application and ensures
 * that the converters can process it without throwing exceptions, and that
 * the output can be decompiled by CFR without throwing exceptions.
 * <p>
 * The original source of the application is available in {@code test-data/app-src/}
 * though it is Kotlin and not Java. Some constructs may not directly map to Java constructs,
 * but the overall control flow and structure should be largely preserved, allowing us to verify
 * that the converters can handle real-world code and produce valid Java bytecode that decompiles correctly.
 */
class SampleConverterTask {
	static final String DECOMPILE_TARGET = "dev/cubxity/apps/streamit/tasks/DownloadImageTask";

	public static void main(String[] args) throws Exception {
		run("");
	}

	static void pruneSample() throws IOException {
		// Read input
		Input input = Input.wrap(Files.readAllBytes(Paths.get("test-data/classes.dex")));
		DexHeaderCodec codec = DexHeader.CODEC;
		DexHeader header = codec.read(input);
		DexMap map = header.map();

		// Prune content
		DexFile dexFile = DexFile.CODEC.map(header, map);
		dexFile.definitions().removeIf(c -> (Opcodes.ACC_ENUM & c.getAccess()) == 0);
		DexMapBuilder mapBuilder = new DexMapBuilder();
		header = DexFile.CODEC.unmap(dexFile, mapBuilder);

		// Write to file
		Output output = Output.wrap();
		codec.write(header, output);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		output.pipe(baos);
		Files.write(Paths.get("test-data/classes-enums.dex"), baos.toByteArray());
	}

	static void run(String prefix) throws Exception {
		// Suppress CFR's logging to the console since we just want to capture the
		// decompilation output and any exceptions it throws, and not be spammed with its internal logging
		System.setErr(new PrintStream(new ByteArrayOutputStream()));

		Input input = Input.wrap(Files.readAllBytes(Paths.get(prefix + "test-data/classes.dex")));
		DexHeaderCodec codec = DexHeader.CODEC;
		DexHeader header = codec.read(input);

		DexMap map = header.map();
		DexFile dexFile = DexFile.CODEC.map(header, map);

		byte[] simpleJar = toJar(dexFile, "simple");
		byte[] irJar = toJar(dexFile, "ir");
		Files.write(Paths.get(prefix + "test-data/classes-converted-simple.jar"), simpleJar);
		Files.write(Paths.get(prefix + "test-data/classes-converted-ir.jar"), irJar);
	}

	public static byte[] toJar(@NotNull DexFile dex, @NotNull String converter) throws IOException {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (JarOutputStream target = new JarOutputStream(baos, manifest)) {
			Set<String> seenErrorKeys = new HashSet<>();
			ConversionResult result = switch (converter) {
				case "simple" -> Converters.SIMPLE.toClasses(dex);
				case "ir" -> Converters.IR.toClasses(dex);
				default -> throw new IllegalArgumentException("Unknown converter: " + converter);
			};
			if (converter.equals("ir")) {
				assertFalse(result.classes().isEmpty(), "IR converter should emit at least one class");
			}
			System.out.println("[" + converter + "] Conversion completed - Input contained " + dex.definitions().size() + " classes");
			System.out.println("[" + converter + "] Converted " + result.classes().size() + " classes with " + result.errors().size() + " failures");
			if (!result.errors().isEmpty()) {
				System.out.println("[" + converter + "] Failed converting " + result.errors().size() + " classes:");
				result.errors().forEach((type, error) -> {
					StackTraceElement[] trace = error.getStackTrace();
					String errorKey = error.getClass().getName() + " at\n " + trace[0] + "\n " + trace[1] + "\n " + trace[2] + "\n " + trace[3];
					if (seenErrorKeys.add(errorKey))
						System.out.println("[" + converter + "] " + type + " : " + TestUtils.traceToString(error));
					else
						System.out.println("[" + converter + "] " + type + " : " + error.getClass().getName() + " : " + error.getMessage());
				});
			}
			result.classes().forEach((type, klass) -> {
				try {
					// Sample output for debugging - decompile the main activity class and print it to the console
					if (type.equals(DECOMPILE_TARGET)) {
						System.out.println("[" + converter + "] Printing sample decompilation of " + type + " to console for debugging purposes...");
						String decompiled = Decompile.decompile(type, klass);
						System.out.println("[" + converter + "] Decompilation of " + type + ":\n" + decompiled);
						assertFalse(decompiled.contains("Exception decompiling"),
								"[" + converter + "] CFR failed to decompile " + type + ":\n" + decompiled);
						assertFalse(decompiled.contains("Decompilation failed"),
								"[" + converter + "] CFR emitted a decompilation failure stub for " + type + ":\n" + decompiled);
					}

					JarEntry entry = new JarEntry(type + ".class");
					target.putNextEntry(entry);

					InputStream in = new ByteArrayInputStream(klass);
					in.transferTo(target);
					target.closeEntry();
				} catch (Throwable t) {
					throw new IllegalStateException("Failed to write class " + type + " to JAR", t);
				}
			});
		}

		return baos.toByteArray();
	}

}
