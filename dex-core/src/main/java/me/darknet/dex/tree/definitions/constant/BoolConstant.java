package me.darknet.dex.tree.definitions.constant;

public record BoolConstant(boolean value) implements Constant {
    public static final BoolConstant TRUE = new BoolConstant(true);
    public static final BoolConstant FALSE = new BoolConstant(false);
}
