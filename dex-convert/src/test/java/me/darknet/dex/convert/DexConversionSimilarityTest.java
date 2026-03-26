package me.darknet.dex.convert;

import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.convert.util.Decompile;
import me.darknet.dex.convert.util.Similarity;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.io.Input;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.util.DexAndJarSource;
import me.darknet.dex.util.TestUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests the similarity of the decompiled output of converted classes to baseline decompilations of the original Java classes.
 */
@Disabled("These will have failures, which is expected. Run these manually to inspect the state of the converter")
class DexConversionSimilarityTest {
	/** Threshold for what we consider a weak match */
	private static final double MATCH_THRESHOLD_WEAK = 0.85;

	private static Set<String> IGNORED_SAMPLES = Set.of(
			// These all take ~15 seconds which is really annoying to run on every test run.
			// Just comment these out if you want to manually verify them.
			"449-checker-bce/classes.dex",
			"104-growth-limit/classes.dex",
			"053-wait-some/classes.dex",
			"021-string2/classes.dex",
			"439-npe/classes.dex"
	);

	@BeforeAll
	static void setup() {
		// Suppress CFR's logging to the console since we just want to capture the
		// decompilation output and any exceptions it throws, and not be spammed with its internal logging
		System.setErr(new PrintStream(new ByteArrayOutputStream()));
	}

	@ParameterizedTest
	@MethodSource("getClasses")
	void testRoundTrip(DexAndJarSource argument) throws Throwable {
		Map<String, byte[]> javaClasses = readJar(argument.javaSource().get());

		Input dexInput = Input.wrap(argument.dexSource().get().readAllBytes());
		DexHeaderCodec codec = DexHeader.CODEC;
		DexHeader header = codec.read(dexInput);

		DexMap map = header.map();
		DexFile dexFile = DexFile.CODEC.map(header, map);

		for (ClassDefinition cls : dexFile.definitions()) {
			String name = cls.getType().internalName();
			if (IGNORED_SAMPLES.contains(argument.name())) {
				Assumptions.assumeFalse(true, "Skipping sample " + argument.name() + " - Explicitly declared.");
				continue;
			}

			// Convert the class to Java bytecode, aborting if that fails.
			// Also get the original baseline Java bytecode for decompilation comparison.
			byte[] jvmCls = Converters.IR.toJavaClass(cls);
			if (jvmCls == null) {
				fail("Converter failed on class " + name + " from dex " + argument.name());
				return;
			}
			byte[] baseCls = javaClasses.get(name);
			Assumptions.assumeTrue(baseCls != null, "No baseline class for " + name + " from dex " + argument.name());

			// Decompile both the converted class and the baseline class.
			// If CFR fails to decompile the original we'll just skip the test. Not much we can do about that.
			// If CFR fails to decompile the converted class, however, that's a failure on our end.
			String decompiledBaseline = Decompile.decompile(name, baseCls);
			String decompiledConverted = Decompile.decompile(name, jvmCls);
			Assumptions.assumeFalse(decompiledBaseline.contains("Decompilation failed"),
					"CFR emitted a decompilation failure stub for '" + name + "' in baseline:\n" + decompiledBaseline);
			if (decompiledConverted.contains("Decompilation failed")) {
				String bytecodeBaseline = Decompile.bytecode(baseCls);
				String bytecodeConverted = Decompile.bytecode(jvmCls);
				fail("CFR emitted a decompilation failure stub in our converted dex -> bytecode for '" + name + "':\n" + decompiledConverted
						+ "\n\nBaseline decompilation:\n" + decompiledBaseline
						+ "\n\nBaseline bytecode:\n" + bytecodeBaseline + "\n\nConverted dex->ir->jvm bytecode (problematic):\n" + bytecodeConverted);
			}

			// Get the AST similarity of the decompiled outputs.
			// We're generally pretty lenient but this should cover wildly incorrect cases.
			double similarity = Similarity.similarity(decompiledBaseline, decompiledConverted);
			String message = "Decompiled class " + name + " from dex " + argument.name() + " is " + (similarity * 100) + "% similar to baseline";
			if (similarity < MATCH_THRESHOLD_WEAK)
				fail(message + "\n\n\nDecompiled baseline:\n" + decompiledBaseline + "\n\n\nDecompiled converted:\n" + decompiledConverted);
			else
				System.out.println(message);
		}
	}

	private static Map<String, byte[]> readJar(InputStream jarStream) throws IOException {
		try (var jar = new JarInputStream(jarStream)) {
			Map<String, byte[]> classes = new java.util.HashMap<>();
			JarEntry entry;
			while ((entry = jar.getNextJarEntry()) != null) {
				if (entry.getName().endsWith(".class")) {
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					jar.transferTo(baos);
					classes.put(entry.getName().replace(".class", "").replace('.', '/'), baos.toByteArray());
				}
			}
			return classes;
		}
	}

	private static List<DexAndJarSource> getClasses() {
		BiPredicate<Path, BasicFileAttributes> filter = (path, attrib) -> attrib.isRegularFile()
				&& path.toString().endsWith(".dex")
				&& !path.getParent().getFileName().toString().equals("test-data")
				&& !isKnownSlow(path);
		return TestUtils.getTestInputs(filter, DexAndJarSource::from);
	}

	private static boolean isKnownSlow(Path path) {
		// Some samples stall decompilation, so we filter those out to keep the test suite fast.
		List<String> knownSlow = List.of("056", "458", "470", "083", "125", "496", "303");
		List<String> knownIgnores = List.of("097", "CST-old-kotlin");
		String testCase = path.getParent().getFileName().toString();
		for (String s : knownSlow)
			if (testCase.contains(s) || knownIgnores.contains(s))
				return true;
		return false;
	}
}
