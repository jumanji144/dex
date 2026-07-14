package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ConversionSupport;
import me.darknet.dex.tree.definitions.MethodMember;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AnalyzerAdapter;

/**
 * Performs instruction-level JVM stack/category checking while lowering.
 * AnalyzerAdapter intentionally remains inside the converter so malformed stack
 * transitions fail before a class is handed to a caller.
 */
final class JvmStackCheckingMethodVisitor {
	private JvmStackCheckingMethodVisitor() {
	}

	static MethodVisitor wrap(MethodMember method, MethodVisitor delegate) {
		return new AnalyzerAdapter(owner(method), method.getAccess(), method.getName(),
				method.getType().descriptor(), delegate);
	}

	private static String owner(MethodMember method) {
		return method.getOwner() == null ? "<unknown>" : ConversionSupport.asmOwner(method.getOwner());
	}
}
