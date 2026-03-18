package me.darknet.dex.util;

import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.io.Input;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.ClassDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestUtils {
	public static final InstructionContext<DexMap> EMPTY_CONTEXT = new InstructionContext<>(
			List.of(),
			List.of(),
			new DexMapBuilder().build(),
			new HashMap<>(),
			null,
			null,
			null
	);
	public static final InstructionContext<DexMapBuilder> EMPTY_CONTEXT_UN = new InstructionContext<>(
			List.of(),
			List.of(),
			new DexMapBuilder(),
			new HashMap<>(),
			null,
			null,
			null
	);

	public static <T> List<T> getTestInputs(BiPredicate<Path, BasicFileAttributes> filter, Function<Path, T> mapper) {
		try {
			Path src = Paths.get(System.getProperty("user.dir")).resolve("../test-data");
			return Files.find(src, 25, filter)
					.map(mapper)
					.collect(Collectors.toList());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static DexSource getDexInput(String sampleName) {
		BiPredicate<Path, BasicFileAttributes> filter = (path, attrib) -> attrib.isRegularFile()
				&& path.toString().endsWith(".dex")
				&& path.getParent().getFileName().toString().equals(sampleName);
		return getTestInputs(filter, DexSource::from).stream().findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No dex file found with name: " + sampleName));
	}

	public static DexHeader getDexHeader(String sampleName) {
		DexSource source = getDexInput(sampleName);
		Input input = assertDoesNotThrow(() -> Input.wrap(source.source().get().readAllBytes()));
		DexHeaderCodec codec = DexHeader.CODEC;
		return assertDoesNotThrow(() -> codec.read(input));
	}

	public static DexFile getDexFile(String sampleName) {
		DexHeader header = getDexHeader(sampleName);
		DexMap map = header.map();
		return DexFile.CODEC.map(header, map);
	}

	public static ClassDefinition getDexDefinition(String sampleName, String className) {
		DexFile dexFile = getDexFile(sampleName);
		ClassDefinition matchDef = null;
		for (ClassDefinition definition : dexFile.definitions()) {
			String name = definition.getType().internalName();
			if (name.equals(className)) {
				matchDef = definition;
				break;
			}
		}
		assertNotNull(matchDef, "Expected to find class " + className + " in dex file");
		return matchDef;
	}

	public static List<DexSource> getDexInputs() {
		BiPredicate<Path, BasicFileAttributes> filter = (path, attrib) -> attrib.isRegularFile()
				&& path.toString().endsWith(".dex");
		return getTestInputs(filter, DexSource::from);
	}

	public static String traceToString(Throwable t) {
		StringBuilder sb = new StringBuilder();
		sb.append(t.toString()).append("\n");
		for (StackTraceElement element : t.getStackTrace()) {
			String elementStr = element.toString();
			if (elementStr.contains("org.junit")) // Break when hitting junit entry point
				break;
			sb.append("\tat ").append(elementStr).append("\n");
		}
		return sb.toString();
	}
}
