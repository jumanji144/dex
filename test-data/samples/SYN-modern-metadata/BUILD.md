# SYN-modern-metadata

Real `d8` output covering the class and method metadata dex-core models as first-class fields:
record components, nest host/members, permitted subclasses, annotation defaults and parameter annotations.

Regenerate with Android build tools (D8 8.6.2):

```
javac --release 21 -d classes src/ex/MetadataFixture.java
d8 --min-api 34 --no-desugaring --output out classes/ex/*.class
cp out/classes.dex .
```

`--no-desugaring` keeps the compiler-written metadata rather than rewriting it, so the fixture stays
close to what javac emits. D8 still consolidates the per-method `AnnotationDefault` attributes javac
writes into a single `dalvik/annotation/AnnotationDefault` annotation on the annotation interface, which
is the encoding the reader distributes back onto the element methods.

Note: D8 8.6.2 desugars records and does not emit `dalvik/annotation/NestHost`, `NestMembers` or
`Record`, so those three encodings are constructed directly in `ModernMetadataTest` instead.
