# SYN-hiddenapi

A dex carrying a hidden API payload, used by `HiddenApiRoundTripTest` and by the corpus-wide
verification run.

The payload is a `hiddenapi_class_data_item`: a total size, one offset per class definition, then the
flags for each class's fields and methods in order. The payload used to start at `11 22 33 44`, which
reads as a size of `0x44332211` and no tooling accepts, so the file only ever passed through this
library's own reader. It was regenerated with a payload that matches the format.

Regenerate by reading the file, replacing its `HiddenApiData` with a payload built for the class count,
and writing it back; the `dex-core` tree API does this directly:

```java
DexFile file = DexFile.CODEC.map(header, header.map());
DexFile fixed = new DexFile(file.version(), file.definitions(), file.link(), new HiddenApiData(1, payload));
DexHeader out = DexFile.CODEC.unmap(fixed, new DexMapBuilder());
DexHeader.CODEC.write(out, output);
```

Verified with `d8` build-tools 35.0.0 `dexdump -f`.
