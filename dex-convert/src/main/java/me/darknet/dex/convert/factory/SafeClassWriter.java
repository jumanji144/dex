package me.darknet.dex.convert.factory;

import org.objectweb.asm.ClassWriter;

/** ClassWriter that computes conservative reference joins without loading DEX-only classes. */
final class SafeClassWriter extends ClassWriter {
	SafeClassWriter(int flags) {
		super(flags);
	}

	@Override
	protected String getCommonSuperClass(String type1, String type2) {
		if (type1.equals(type2)) return type1;
		if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) return "java/lang/Object";
		if (type1.startsWith("[") && type2.startsWith("[") && type1.equals(type2)) return type1;
		return "java/lang/Object";
	}
}
