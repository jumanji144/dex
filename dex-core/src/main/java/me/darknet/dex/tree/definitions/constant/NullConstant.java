package me.darknet.dex.tree.definitions.constant;

public record NullConstant() implements Constant {
    public static final NullConstant INSTANCE = new NullConstant();
}
