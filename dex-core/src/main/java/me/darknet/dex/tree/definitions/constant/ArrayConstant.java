package me.darknet.dex.tree.definitions.constant;

import java.util.List;

public record ArrayConstant(List<Constant> constants) implements Constant {
}
