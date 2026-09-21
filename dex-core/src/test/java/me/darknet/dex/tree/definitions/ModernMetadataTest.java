package me.darknet.dex.tree.definitions;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.file.items.AnnotationsDirectoryItem;
import me.darknet.dex.file.items.AnnotationOffItem;
import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.file.items.TypeListItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.constant.AnnotationConstant;
import me.darknet.dex.tree.definitions.constant.ArrayConstant;
import me.darknet.dex.tree.definitions.constant.ByteConstant;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.NullConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.definitions.constant.TypeConstant;
import me.darknet.dex.tree.type.Types;
import me.darknet.dex.util.TestUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the metadata encodings dex-core models as first-class fields.
 *
 * <p>The fixture under {@code test-data/samples/SYN-modern-metadata} is real {@code d8} output, so the
 * assertions on it pin the reader to the wire format Android tooling actually writes. Encodings D8 does not
 * emit are written through the file layer here, which keeps those assertions independent of the tree export
 * path they are meant to check.
 */
class ModernMetadataTest {

    private static final String FIXTURE = "SYN-modern-metadata";
    private static final byte SYSTEM = (byte) Annotation.VISIBILITY_SYSTEM;
    private static final byte RUNTIME = (byte) Annotation.VISIBILITY_RUNTIME;

    @Test
    void readsAnnotationDefaultsFromRealDex() {
        DexFile file = TestUtils.getDexFile(FIXTURE);
        ClassDefinition defaults = definition(file, "ex/MetadataFixture$Defaults");

        // javac puts one AnnotationDefault attribute on each element; D8 folds them into a single annotation
        // on the interface, so reading them back per element proves the distribution works.
        assertEquals(new StringConstant("fallback"),
                defaults.getMethod("name", "()Ljava/lang/String;").getDefaultValue());
        assertEquals(new IntConstant(7), defaults.getMethod("count", "()I").getDefaultValue());
        assertEquals(new TypeConstant(Types.instanceTypeFromDescriptor("Ljava/lang/String;")),
                defaults.getMethod("type", "()Ljava/lang/Class;").getDefaultValue());
        assertEquals(new ArrayConstant(List.of(new StringConstant("a"), new StringConstant("b"))),
                defaults.getMethod("tags", "()[Ljava/lang/String;").getDefaultValue());

        // The annotation is consumed, so it must not linger as a raw annotation on the class.
        assertFalse(hasAnnotation(defaults, "dalvik/annotation/AnnotationDefault"));
    }

    @Test
    void readsPermittedSubclassesFromRealDex() {
        ClassDefinition shape = definition(TestUtils.getDexFile(FIXTURE), "ex/MetadataFixture$Shape");

        assertEquals(List.of(
                Types.instanceTypeFromInternalName("ex/MetadataFixture$Circle"),
                Types.instanceTypeFromInternalName("ex/MetadataFixture$Square")
        ), shape.getPermittedSubclasses());
        assertFalse(hasAnnotation(shape, "dalvik/annotation/PermittedSubclasses"));
    }

    @Test
    void readsParameterAnnotationsFromRealDex() {
        ClassDefinition fixture = definition(TestUtils.getDexFile(FIXTURE), "ex/MetadataFixture");

        // The second parameter is unannotated, so it has to survive as an empty list rather than collapsing
        // and shifting the annotated parameter out of its slot.
        List<List<Annotation>> parameters =
                fixture.getMethod("parameterAnnotations", "(II)I").getParameterAnnotations();
        assertEquals(2, parameters.size());
        assertEquals(List.of(fixtureComponentAnnotation(3)), parameters.get(0));
        assertTrue(parameters.get(1).isEmpty());

        // The record constructor mixes annotated and unannotated parameters across four slots.
        List<List<Annotation>> constructor = definition(TestUtils.getDexFile(FIXTURE), "ex/MetadataFixture$Point")
                .getMethods().values().stream()
                .filter(method -> method.getName().equals("<init>"))
                .findFirst()
                .orElseThrow()
                .getParameterAnnotations();
        assertEquals(4, constructor.size());
        assertEquals(List.of(fixtureComponentAnnotation(1)), constructor.get(0));
        assertTrue(constructor.get(1).isEmpty());
        assertEquals(List.of(fixtureComponentAnnotation(2)), constructor.get(2));
        assertTrue(constructor.get(3).isEmpty());
    }

    @Test
    void realDexMetadataSurvivesATreeRoundTrip() throws Exception {
        DexFile file = TestUtils.getDexFile(FIXTURE);

        DexHeader header = DexFile.CODEC.unmap(file, new DexMapBuilder());
        Output output = Output.wrap();
        DexHeader.CODEC.write(header, output);
        DexHeader roundTrippedHeader = DexHeader.CODEC.read(Input.wrap(output.buffer()));
        DexFile roundTripped = DexFile.CODEC.map(roundTrippedHeader, roundTrippedHeader.map());

        ClassDefinition defaults = definition(roundTripped, "ex/MetadataFixture$Defaults");
        assertEquals(new IntConstant(7), defaults.getMethod("count", "()I").getDefaultValue());
        assertEquals(new StringConstant("fallback"),
                defaults.getMethod("name", "()Ljava/lang/String;").getDefaultValue());

        assertEquals(List.of(
                Types.instanceTypeFromInternalName("ex/MetadataFixture$Circle"),
                Types.instanceTypeFromInternalName("ex/MetadataFixture$Square")
        ), definition(roundTripped, "ex/MetadataFixture$Shape").getPermittedSubclasses());

        assertEquals(List.of(List.of(fixtureComponentAnnotation(3)), List.of()),
                definition(roundTripped, "ex/MetadataFixture").getMethod("parameterAnnotations", "(II)I")
                        .getParameterAnnotations());
    }

    @Test
    void readsNestHostWrittenByTheFileLayer() throws Exception {
        // D8 8.6.2 does not emit nest attributes, so the encoding is built here from the documented format.
        ClassDefinition definition = readSingleClass(systemAnnotation("dalvik/annotation/NestHost",
                Map.of("value", new TypeConstant(Types.instanceTypeFromInternalName("example/Host")))));

        assertEquals(Types.instanceTypeFromInternalName("example/Host"), definition.getNestHost());
        assertFalse(hasAnnotation(definition, "dalvik/annotation/NestHost"));
    }

    @Test
    void readsNestMembersWrittenByTheFileLayer() throws Exception {
        ClassDefinition definition = readSingleClass(systemAnnotation("dalvik/annotation/NestMembers",
                Map.of("value", new ArrayConstant(List.of(
                        new TypeConstant(Types.instanceTypeFromInternalName("example/Host$A")),
                        new TypeConstant(Types.instanceTypeFromInternalName("example/Host$B")))))));

        assertEquals(List.of(
                Types.instanceTypeFromInternalName("example/Host$A"),
                Types.instanceTypeFromInternalName("example/Host$B")
        ), definition.getNestMembers());
        assertFalse(hasAnnotation(definition, "dalvik/annotation/NestMembers"));
    }

    @Test
    void readsPermittedSubclassesWrittenByTheFileLayer() throws Exception {
        ClassDefinition definition = readSingleClass(systemAnnotation("dalvik/annotation/PermittedSubclasses",
                Map.of("value", new ArrayConstant(List.of(
                        new TypeConstant(Types.instanceTypeFromInternalName("example/Shape$Circle")))))));

        assertEquals(List.of(Types.instanceTypeFromInternalName("example/Shape$Circle")),
                definition.getPermittedSubclasses());
        assertFalse(hasAnnotation(definition, "dalvik/annotation/PermittedSubclasses"));
    }

    @Test
    void readsRecordComponentsWrittenByTheFileLayer() throws Exception {
        // The format keeps one array per component property rather than one entry per component, so the
        // reader has to transpose the parallel arrays back into components.
        Constant signature = new AnnotationConstant(new AnnotationPart(
                Types.instanceTypeFromInternalName("dalvik/annotation/Signature"),
                Map.of("value", new ArrayConstant(List.of(new StringConstant("TT;"))))));
        ClassDefinition definition = readSingleClass(systemAnnotation("dalvik/annotation/Record", Map.of(
                "componentNames", new ArrayConstant(List.of(
                        new StringConstant("name"), new StringConstant("age"))),
                "componentTypes", new ArrayConstant(List.of(
                        new TypeConstant(Types.instanceTypeFromDescriptor("Ljava/lang/String;")),
                        new TypeConstant(Types.INT))),
                "componentSignatures", new ArrayConstant(List.of(signature, NullConstant.INSTANCE)),
                "componentAnnotationVisibilities", new ArrayConstant(List.of(
                        new ArrayConstant(List.of(new ByteConstant((byte) 1))),
                        new ArrayConstant(List.of()))),
                "componentAnnotations", new ArrayConstant(List.of(
                        new ArrayConstant(List.of(new AnnotationConstant(new AnnotationPart(
                                Types.instanceTypeFromInternalName("example/ComponentAnn"),
                                Map.of("value", new IntConstant(9)))))),
                        new ArrayConstant(List.of())))
        )));

        assertEquals(2, definition.getRecordComponents().size());

        RecordComponent name = definition.getRecordComponents().get(0);
        assertEquals("name", name.name());
        assertEquals(Types.instanceTypeFromDescriptor("Ljava/lang/String;"), name.type());
        assertEquals("TT;", name.signature());
        assertEquals(1, name.annotations().size());
        assertEquals(Types.instanceTypeFromInternalName("example/ComponentAnn"),
                name.annotations().getFirst().annotation().type());
        assertEquals(1, name.annotations().getFirst().visibility());

        // An unannotated component carries no annotations, and a null signature stays null.
        RecordComponent age = definition.getRecordComponents().get(1);
        assertEquals("age", age.name());
        assertEquals(Types.INT, age.type());
        assertNull(age.signature());
        assertTrue(age.annotations().isEmpty());

        assertFalse(hasAnnotation(definition, "dalvik/annotation/Record"));
    }

    @Test
    void recordComponentsSurviveATreeRoundTrip() throws Exception {
        ClassDefinition definition = new ClassDefinition(
                Types.instanceTypeFromInternalName("example/Rec"), Types.OBJECT, 0x0001);
        definition.addRecordComponent(new RecordComponent("name",
                Types.instanceTypeFromDescriptor("Ljava/lang/String;"), "TT;", List.of(
                new Annotation(RUNTIME, new AnnotationPart(
                        Types.instanceTypeFromInternalName("example/ComponentAnn"),
                        Map.of("value", new IntConstant(9)))))));
        definition.addRecordComponent(new RecordComponent("age", Types.INT, null, List.of()));

        DexFile dexFile = new DexFile(39, List.of(definition));
        DexHeader header = DexFile.CODEC.unmap(dexFile, new DexMapBuilder());
        Output output = Output.wrap();
        DexHeader.CODEC.write(header, output);
        DexHeader roundTrippedHeader = DexHeader.CODEC.read(Input.wrap(output.buffer()));
        DexFile roundTripped = DexFile.CODEC.map(roundTrippedHeader, roundTrippedHeader.map());

        assertEquals(definition.getRecordComponents(),
                roundTripped.definitions().getFirst().getRecordComponents());
    }

    @Test
    void annotationDefaultWithUnknownElementIsPreservedRatherThanDropped() throws Exception {
        // A binding with no matching element method cannot be modelled, so the annotation has to survive as a
        // raw annotation instead of being consumed and losing the binding.
        ClassDefinition definition = readSingleClass(systemAnnotation("dalvik/annotation/AnnotationDefault",
                Map.of("value", new AnnotationConstant(new AnnotationPart(
                        Types.instanceTypeFromInternalName("example/Defaults"),
                        Map.of("missing", new IntConstant(1)))))));

        assertTrue(hasAnnotation(definition, "dalvik/annotation/AnnotationDefault"));
    }

    @Test
    void malformedRecordAnnotationIsPreservedRatherThanDropped() throws Exception {
        // Mismatched array lengths cannot be transposed into components, so the annotation has to survive raw
        // instead of being consumed and losing the data.
        ClassDefinition definition = readSingleClass(systemAnnotation("dalvik/annotation/Record", Map.of(
                "componentNames", new ArrayConstant(List.of(new StringConstant("name"))),
                "componentTypes", new ArrayConstant(List.of()),
                "componentSignatures", new ArrayConstant(List.of(NullConstant.INSTANCE)),
                "componentAnnotationVisibilities", new ArrayConstant(List.of(new ArrayConstant(List.of()))),
                "componentAnnotations", new ArrayConstant(List.of(new ArrayConstant(List.of())))
        )));

        assertTrue(definition.getRecordComponents().isEmpty());
        assertTrue(hasAnnotation(definition, "dalvik/annotation/Record"));
    }

    /**
     * @param file
     * 		File to search.
     * @param internalName
     * 		Internal name of the class to find.
     *
     * @return The matching class definition.
     */
    private static ClassDefinition definition(DexFile file, String internalName) {
        return file.definitions().stream()
                .filter(definition -> definition.getType().internalName().equals(internalName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No class " + internalName + " in fixture"));
    }

    private static boolean hasAnnotation(ClassDefinition definition, String type) {
        return definition.getAnnotations().stream()
                .anyMatch(annotation -> type.equals(annotation.annotation().type().internalName()));
    }

    private static Annotation fixtureComponentAnnotation(int value) {
        return new Annotation(RUNTIME, new AnnotationPart(
                Types.instanceTypeFromInternalName("ex/MetadataFixture$ComponentAnn"),
                Map.of("value", new IntConstant(value))));
    }

    private static Annotation systemAnnotation(String internalName, Map<String, Constant> elements) {
        return new Annotation(SYSTEM, new AnnotationPart(
                Types.instanceTypeFromInternalName(internalName), elements));
    }

    /**
     * @param annotation
     * 		Annotation to place on the class.
     *
     * @return The class definition the annotation was attached to, as read back by the tree codec.
     *
     * @throws Exception
     * 		If the class cannot be written, decoded or read back.
     */
    private static ClassDefinition readSingleClass(Annotation annotation) throws Exception {
        DexMapBuilder builder = new DexMapBuilder();

        // The annotation and its set have to be registered so the writer can give them offsets.
        AnnotationSetItem set = builder.annotationSet(List.of(annotation));
        AnnotationsDirectoryItem directory = new AnnotationsDirectoryItem(set, List.of(), List.of(), List.of());
        builder.annotationsDirectories().add(directory);

        ClassDefItem item = new ClassDefItem(builder.type("Lexample/Subject;"), 0x0001,
                builder.type("Ljava/lang/Object;"), TypeListItem.EMPTY, null, directory, null, null);
        builder.classes().add(item);

        DexMap map = builder.build();
        Output output = Output.wrap();
        DexHeader.CODEC.write(new DexHeader(39, new byte[0], map), output);
        DexHeader header = DexHeader.CODEC.read(Input.wrap(output.buffer()));
        DexFile file = DexFile.CODEC.map(header, header.map());

        assertEquals(1, file.definitions().size());
        return file.definitions().getFirst();
    }

}
