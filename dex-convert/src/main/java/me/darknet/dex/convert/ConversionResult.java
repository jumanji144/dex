package me.darknet.dex.convert;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * @param classes
 * 		Resulting class bytecode, mapped by internal class name.
 * @param errors
 * 		Any errors that occurred during conversion, mapped by internal class name.
 */
public record ConversionResult(@NotNull Map<String, byte[]> classes,
                               @NotNull Map<String, Throwable> errors) {}
