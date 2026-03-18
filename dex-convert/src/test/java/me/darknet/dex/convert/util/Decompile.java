package me.darknet.dex.convert.util;

import me.darknet.dex.convert.ir.IrLowering;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.util.TestUtils;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;

public class Decompile {
	public static String decompile(String name, IrMethod ir) {
		byte[] bytecode = compile(name, ir);
		return decompile(name, bytecode);
	}

	public static String decompile(String name, byte[] bytecode) {
		return decompile(name, Map.of(name, bytecode));
	}

	public static String decompile(String name, Map<String, byte[]> classes) {
		ClassSource source = new ClassSource(classes);
		SinkFactoryImpl sink = new SinkFactoryImpl();
		CfrDriver driver = new CfrDriver.Builder()
				.withClassFileSource(source)
				.withOutputSink(sink)
				.withOptions(Map.of(
						"comments", "false",
						"version", "false"))
				.build();
		driver.analyse(Collections.singletonList(name));
		Throwable exception = sink.getException();
		assertNull(exception, exception == null
				? "CFR threw while decompiling " + name
				: "CFR threw while decompiling " + name + ": " + TestUtils.traceToString(exception));
		return sink.getDecompilation();
	}

	public static String bytecode(String name, IrMethod ir) {
		byte[] bytecode = compile(name, ir);
		return bytecode(bytecode);
	}

	public static String bytecode(byte[] cls) {
		ClassReader reader = new ClassReader(cls);
		StringWriter stringWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(stringWriter);
		ClassVisitor traceClassVisitor = new TraceClassVisitor(printWriter);
		reader.accept(traceClassVisitor, 0);
		return stringWriter.toString();
	}

	private static byte[] compile(String name, IrMethod ir) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);

		MethodVisitor mw = cw.visitMethod(ir.source().getAccess(), "test", ir.source().getType().descriptor(), null, null);
		IrLowering.emit(ir, mw);

		cw.visitEnd();
		return cw.toByteArray();
	}

	public static class ClassSource implements ClassFileSource {
		private final Map<String, byte[]> classes;

		public ClassSource(Map<String, byte[]> classes) {
			this.classes = classes;
		}

		@Override
		public void informAnalysisRelativePathDetail(String usePath, String specPath) {
		}

		@Override
		public Collection<String> addJar(String jarPath) {
			return Collections.emptySet();
		}

		@Override
		public String getPossiblyRenamedPath(String path) {
			return path;
		}

		@Override
		public Pair<byte[], String> getClassFileContent(String inputPath) {
			String className = inputPath.substring(0, inputPath.indexOf(".class"));
			byte[] code = classes.get(className);
			if (code == null) {
				// If the class is a standard library class,
				// we can try to load it from the classpath.
				try {
					if (className.startsWith("java/"))
						code = new ClassReader(className).b;
				} catch (Throwable ignored) {}
			}
			return new Pair<>(code, inputPath);
		}
	}

	public static class SinkFactoryImpl implements OutputSinkFactory {
		private Throwable exception;
		private String decompile;

		@Override
		public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> collection) {
			return Arrays.asList(SinkClass.values());
		}

		@Override
		public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
			return switch (sinkType) {
				case JAVA -> this::setDecompilation;
				case EXCEPTION -> this::handleException;
				default -> t -> {
				};
			};
		}

		private <T> void handleException(@Nullable T value) {
			if (value instanceof Throwable) {
				exception = (Throwable) value;
			} else {
				System.err.println("CFR encountered an error but provided no additional information");
			}
		}

		private <T> void setDecompilation(T value) {
			decompile = value.toString();
		}

		/**
		 * @return Decompiled class content.
		 */
		@Nullable
		public String getDecompilation() {
			return decompile;
		}

		/**
		 * @return Failure reason.
		 */
		@Nullable
		public Throwable getException() {
			return exception;
		}
	}
}
