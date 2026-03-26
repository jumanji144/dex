package me.darknet.dex.tree.definitions.debug;

import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.type.Type;

import java.util.List;

public record DebugInformation(List<LineNumber> lineNumbers, List<String> parameterNames, List<LocalVariable> locals) {

    public record LocalVariable(int register, String name, Type type, String signature, Label start, Label end) {}

    public record LineNumber(Label label, int line) {}

}
