package me.darknet.dex.convert;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Performs the mandatory structural ASM verification pass for generated classes. */
final class JvmClassVerifier {
	private JvmClassVerifier() {
	}

	static Verification verify(byte[] bytecode) {
		StringWriter report = new StringWriter();
		try {
			CheckClassAdapter.verify(new ClassReader(bytecode), false, new PrintWriter(report));
		} catch (TypeNotPresentException | LinkageError unavailable) {
			return new Verification(false, unavailable, true);
		} catch (RuntimeException failure) {
			throw new IllegalStateException("Generated class failed ASM structural verification", failure);
		}
		String text = report.toString();
		if (text.contains("TypeNotPresentException") || text.contains("ClassNotFoundException"))
			return new Verification(false, new IllegalStateException(text), true);
		if (text.contains("AnalyzerException") || text.contains("Exception in thread")) {
			throw new IllegalStateException("Generated class failed ASM verification:\n" + text);
		}
		return new Verification(true, null, false);
	}

	record Verification(boolean valid, Throwable unavailable, boolean dependencyUnavailable) {
	}
}
