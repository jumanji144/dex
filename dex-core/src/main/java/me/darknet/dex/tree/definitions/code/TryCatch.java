package me.darknet.dex.tree.definitions.code;

import me.darknet.dex.tree.definitions.instructions.Label;

import java.util.List;

public record TryCatch(Label begin, Label end, List<Handler> handlers) {

}
