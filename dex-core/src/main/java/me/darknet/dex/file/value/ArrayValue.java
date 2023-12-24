package me.darknet.dex.file.value;

import java.util.List;

public record ArrayValue(List<Value> values) implements Value {
}
