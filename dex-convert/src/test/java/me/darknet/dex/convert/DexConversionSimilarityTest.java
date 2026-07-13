package me.darknet.dex.convert;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests each converted DEX class against its corresponding baseline decompilation.
 */
@Disabled("These will have failures, which is expected. Run these manually to inspect the state of the converter")
class DexConversionSimilarityTest {
	/** Threshold for what we consider a weak match */
	private static final double MATCH_THRESHOLD_WEAK = 0.85;
	private static final Set<String> IGNORED_SAMPLES = Set.of(
			// These all take ~15 seconds which is really annoying to run on every test run.
			// Just comment these out if you want to manually verify them.
			"449-checker-bce/classes.dex",
			"104-growth-limit/classes.dex",
			"053-wait-some/classes.dex",
			"021-string2/classes.dex",
			"439-npe/classes.dex"
	);
	private static final List<String> KNOWN_SLOW_SAMPLES = List.of("056", "458", "470", "083", "125", "496", "303");
	private static final List<String> MISSING_REFERENCE_JAR_SAMPLES = List.of("097-duplicate-method", "CST-old-kotlin-enum");

	@BeforeAll
	static void setup() {
		System.setErr(new PrintStream(new ByteArrayOutputStream()));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("getClasses")
	void testRoundTrip(DexClassCase argument) throws Throwable {
		String name = argument.internalClassName();
		byte[] baseCls = argument.baselineBytes();
		byte[] jvmCls = Converters.IR.toJavaClass(argument.classDefinition());
		if (jvmCls == null) {
			fail("Converter failed on class " + name + " from dex " + argument.sampleName());
			return;
		}
		Assumptions.assumeTrue(baseCls != null, "No baseline class for " + name + " from dex " + argument.sampleName());

		String decompiledBaseline = Decompile.decompile(name, baseCls);
		String decompiledConverted = Decompile.decompile(name, jvmCls);
		Assumptions.assumeFalse(decompiledBaseline.contains("Decompilation failed"),
				"CFR emitted a decompilation failure stub for '" + name + "' in baseline:\n" + decompiledBaseline);
		if (decompiledConverted.contains("Decompilation failed")) {
			fail("CFR emitted a decompilation failure stub in converted dex -> bytecode for '" + name + "':\n"
					+ decompiledConverted + "\n\nBaseline decompilation:\n" + decompiledBaseline
					+ "\n\nBaseline bytecode:\n" + Decompile.bytecode(baseCls)
					+ "\n\nConverted bytecode:\n" + Decompile.bytecode(jvmCls));
		}

		double similarity = Similarity.similarity(decompiledBaseline, decompiledConverted);
		String message = "Decompiled class " + name + " from dex " + argument.sampleName() + " is "
				+ (similarity * 100) + "% similar to baseline";
		if (similarity < MATCH_THRESHOLD_WEAK)
			fail(message + "\n\nBaseline:\n" + decompiledBaseline + "\n\nConverted:\n" + decompiledConverted);
		else
			System.out.println(message);
	}

	private static Map<String, byte[]> readJar(InputStream jarStream) throws IOException {
		try (var jar = new JarInputStream(jarStream)) {
			Map<String, byte[]> classes = new java.util.HashMap<>();
			JarEntry entry;
			while ((entry = jar.getNextJarEntry()) != null) {
				if (!entry.getName().endsWith(".class")) continue;
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				jar.transferTo(bytes);
				classes.put(entry.getName().replace(".class", "").replace('.', '/'), bytes.toByteArray());
			}
			return classes;
		}
	}

	private static List<DexClassCase> getClasses() throws Throwable {
		BiPredicate<Path, BasicFileAttributes> filter = (path, attributes) -> attributes.isRegularFile()
				&& path.toString().endsWith(".dex")
				&& !path.getParent().getFileName().toString().equals("test-data")
				&& !isExcludedSample(path);
		List<DexClassCase> cases = new ArrayList<>();
		for (DexAndJarSource sample : TestUtils.getTestInputs(filter, DexAndJarSource::from)) {
			Map<String, byte[]> baselines;
			try (InputStream jar = sample.javaSource().get()) {
				baselines = readJar(jar);
			}
			try (InputStream dex = sample.dexSource().get()) {
				DexHeader header = DexHeader.CODEC.read(Input.wrap(dex.readAllBytes()));
				DexMap map = header.map();
				DexFile dexFile = DexFile.CODEC.map(header, map);
				for (ClassDefinition cls : dexFile.definitions())
					cases.add(new DexClassCase(sample.name(), cls, cls.getType().internalName(), baselines.get(cls.getType().internalName())));
			}
		}
		return cases;
	}

	private static boolean isExcludedSample(Path path) {
		String samplePath = path.getParent().getFileName() + "/" + path.getFileName();
		if (IGNORED_SAMPLES.contains(samplePath)) return true;
		String sample = path.getParent().getFileName().toString();
		return KNOWN_SLOW_SAMPLES.stream().anyMatch(sample::contains)
				|| MISSING_REFERENCE_JAR_SAMPLES.stream().anyMatch(sample::contains)
				|| !Files.exists(path.getParent().resolve("classes.jar"));
	}

	private record DexClassCase(String sampleName, ClassDefinition classDefinition, String internalClassName,
	                            byte[] baselineBytes) {
		@Override
		public String toString() {
			return sampleName + ":" + internalClassName;
		}
	}
}
