package me.darknet.dex.tree.definitions.constant;

import me.darknet.dex.tree.type.MethodType;

/**
 * A constant holding a method prototype, as produced by {@code const-method-type} or by a call site bootstrap argument.
 *
 * @param type
 * 		Prototype this constant names.
 */
public record MethodTypeConstant(MethodType type) implements Constant {}