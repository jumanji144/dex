package me.darknet.dex.convert;

/**
 * Shared conversion instances for testing.
 */
public class Converters {
	/**
	 * Shared simple conversion instance.
	 */
	public static final DexConversion SIMPLE = new DexConversionSimple();
	/**
	 * Shared IR-based conversion instance.
	 */
	public static final DexConversion IR = new DexConversionIr();
}
