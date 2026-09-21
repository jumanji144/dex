package me.darknet.dex.tree.definitions.annotation;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.AccessFlags;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.RecordComponent;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationProcessingTest implements AccessFlags {

    @Test
    void consumesAndReemitsSupportedClassMetadata() throws Exception {
        ClassDefinition definition = newDefinition();
        definition.setSignature("<T:Ljava/lang/Object;>Ljava/lang/Object;");
        definition.setEnclosingClass(Types.instanceTypeFromInternalName("example/Outer"));
        definition.setEnclosingMethod(new MemberIdentifier("factory", Types.methodTypeFromDescriptor("()V")));
        definition.addInnerClass(new me.darknet.dex.tree.definitions.InnerClass(
                "example/Outer$Inner", "example/Outer", "Inner", ACC_PUBLIC
        ));
        definition.addMemberClass(Types.instanceTypeFromInternalName("example/Outer$Inner$Child"));

        ClassDefinition roundTrippedDefinition = roundTrip(definition).definitions().getFirst();

        assertEquals(definition.getSignature(), roundTrippedDefinition.getSignature());
        assertEquals(definition.getEnclosingClass(), roundTrippedDefinition.getEnclosingClass());
        assertEquals(definition.getEnclosingMethod(), roundTrippedDefinition.getEnclosingMethod());
        assertEquals(definition.getMemberClasses(), roundTrippedDefinition.getMemberClasses());
        assertFalse(hasAnnotation(roundTrippedDefinition, "dalvik/annotation/Signature"));
    }

    @Test
    void consumesAndReemitsNestMetadata() throws Exception {
        ClassDefinition definition = newDefinition();
        definition.setNestHost(Types.instanceTypeFromInternalName("example/Outer"));
        definition.addNestMember(Types.instanceTypeFromInternalName("example/Outer$Sibling"));
        definition.addPermittedSubclass(Types.instanceTypeFromInternalName("example/Outer$Impl"));

        ClassDefinition roundTrippedDefinition = roundTrip(definition).definitions().getFirst();

        assertEquals(definition.getNestHost(), roundTrippedDefinition.getNestHost());
        assertEquals(definition.getNestMembers(), roundTrippedDefinition.getNestMembers());
        assertEquals(definition.getPermittedSubclasses(), roundTrippedDefinition.getPermittedSubclasses());
        // The metadata is carried by the model fields now, so it must not also linger as a raw annotation.
        assertFalse(hasAnnotation(roundTrippedDefinition, "dalvik/annotation/NestHost"));
        assertFalse(hasAnnotation(roundTrippedDefinition, "dalvik/annotation/NestMembers"));
        assertFalse(hasAnnotation(roundTrippedDefinition, "dalvik/annotation/PermittedSubclasses"));
    }

    @Test
    void preservesUnrecognisedSystemAnnotationsAsRawAnnotations() throws Exception {
        ClassDefinition definition = newDefinition();
        definition.addAnnotation(new Annotation((byte) Annotation.VISIBILITY_SYSTEM, new AnnotationPart(
                Types.instanceTypeFromInternalName("dalvik/annotation/SourceDebugExtension"),
                Map.of("value", new StringConstant("SMAP\n"))
        )));

        ClassDefinition roundTrippedDefinition = roundTrip(definition).definitions().getFirst();

        assertTrue(hasAnnotation(roundTrippedDefinition, "dalvik/annotation/SourceDebugExtension"));
    }

    @Test
    void consumesAndReemitsSupportedMethodMetadataWhilePreservingUnrecognisedRawAnnotations() throws Exception {
        ClassDefinition definition = newDefinition();
        MethodMember method = new MethodMember("work", Types.methodTypeFromDescriptor("(Ljava/lang/String;I)V"), ACC_PUBLIC);
        method.setSignature("(Ljava/lang/String;I)V");
        method.setThrownTypes(List.of("java/io/IOException"));
        method.setParameterNames(Arrays.asList("name", null));
        method.setParameterAccessFlags(List.of(ACC_FINAL, 0));
        method.addAnnotation(new Annotation((byte) Annotation.VISIBILITY_SYSTEM, new AnnotationPart(
                Types.instanceTypeFromInternalName("dalvik/annotation/SourceDebugExtension"),
                Map.of("value", new StringConstant("SMAP\n"))
        )));
        definition.putMethod(method);

        MethodMember roundTrippedMethod = roundTrip(definition).definitions().getFirst().getMethods().values().iterator().next();

        assertEquals(List.of("java/io/IOException"), roundTrippedMethod.getThrownTypes());
        assertEquals(Arrays.asList("name", null), roundTrippedMethod.getParameterNames());
        assertEquals(List.of(ACC_FINAL, 0), roundTrippedMethod.getParameterAccessFlags());
        assertTrue(hasAnnotation(roundTrippedMethod, "dalvik/annotation/SourceDebugExtension"));
        assertFalse(hasAnnotation(roundTrippedMethod, "dalvik/annotation/AnnotationDefault"));
        assertFalse(hasAnnotation(roundTrippedMethod, "dalvik/annotation/Throws"));
        assertFalse(hasAnnotation(roundTrippedMethod, "dalvik/annotation/MethodParameters"));
        assertFalse(hasAnnotation(roundTrippedMethod, "dalvik/annotation/Signature"));
    }

    @Test
    void consumesAndReemitsAnnotationInterfaceDefaults() throws Exception {
        ClassDefinition definition = newDefinition();
        MethodMember named = new MethodMember("name", Types.methodTypeFromDescriptor("()Ljava/lang/String;"), ACC_PUBLIC);
        named.setDefaultValue(new StringConstant("fallback"));
        MethodMember counted = new MethodMember("count", Types.methodTypeFromDescriptor("()I"), ACC_PUBLIC);
        counted.setDefaultValue(new IntConstant(7));
        // An element without a default must not gain one.
        MethodMember plain = new MethodMember("plain", Types.methodTypeFromDescriptor("()I"), ACC_PUBLIC);
        definition.putMethod(named);
        definition.putMethod(counted);
        definition.putMethod(plain);

        ClassDefinition roundTrippedDefinition = roundTrip(definition).definitions().getFirst();

        assertEquals(new StringConstant("fallback"),
                roundTrippedDefinition.getMethod("name", "()Ljava/lang/String;").getDefaultValue());
        assertEquals(new IntConstant(7), roundTrippedDefinition.getMethod("count", "()I").getDefaultValue());
        assertNull(roundTrippedDefinition.getMethod("plain", "()I").getDefaultValue());
        assertFalse(hasAnnotation(roundTrippedDefinition, "dalvik/annotation/AnnotationDefault"));
    }

    @Test
    void roundTripsRecordComponentsWithSignaturesAndAnnotations() throws Exception {
        ClassDefinition definition = newDefinition();
        definition.addRecordComponent(new RecordComponent("name", Types.instanceTypeFromDescriptor("Ljava/lang/String;"),
                null, List.of()));
        definition.addRecordComponent(new RecordComponent("age", Types.INT, "TT;", List.of(
                new Annotation((byte) Annotation.VISIBILITY_RUNTIME, new AnnotationPart(
                        Types.instanceTypeFromInternalName("example/ComponentAnn"),
                        Map.of("value", new IntConstant(3))
                ))
        )));

        ClassDefinition roundTrippedDefinition = roundTrip(definition).definitions().getFirst();

        assertEquals(definition.getRecordComponents(), roundTrippedDefinition.getRecordComponents());
        assertFalse(hasAnnotation(roundTrippedDefinition, "dalvik/annotation/Record"));
    }

    @Test
    void roundTripsParameterAnnotationsOnMethods() throws Exception {
        ClassDefinition definition = newDefinition();
        MethodMember method = new MethodMember("work", Types.methodTypeFromDescriptor("(Ljava/lang/String;I)V"), ACC_PUBLIC);
        // Three parameters to prove unannotated ones keep their slot instead of collapsing the list.
        MethodMember wide = new MethodMember("wide",
                Types.methodTypeFromDescriptor("(Ljava/lang/String;ILjava/lang/Object;)V"), ACC_PUBLIC);
        method.setParameterAnnotations(List.of(
                List.of(new Annotation((byte) Annotation.VISIBILITY_RUNTIME, new AnnotationPart(
                        Types.instanceTypeFromInternalName("example/ParamAnn"),
                        Map.of("value", new IntConstant(1))
                ))),
                List.of()
        ));
        wide.setParameterAnnotations(List.of(
                List.of(),
                List.of(new Annotation((byte) Annotation.VISIBILITY_RUNTIME, new AnnotationPart(
                        Types.instanceTypeFromInternalName("example/ParamAnn"),
                        Map.of("value", new IntConstant(2))
                ))),
                List.of()
        ));
        definition.putMethod(method);
        definition.putMethod(wide);

        ClassDefinition roundTrippedDefinition = roundTrip(definition).definitions().getFirst();

        assertEquals(method.getParameterAnnotations(),
                roundTrippedDefinition.getMethod("work", "(Ljava/lang/String;I)V").getParameterAnnotations());
        assertEquals(wide.getParameterAnnotations(),
                roundTrippedDefinition.getMethod("wide",
                        "(Ljava/lang/String;ILjava/lang/Object;)V").getParameterAnnotations());
    }

    @Test
    void methodsWithoutParameterAnnotationsRoundTripUnchanged() throws Exception {
        ClassDefinition definition = newDefinition();
        definition.putMethod(new MethodMember("plain", Types.methodTypeFromDescriptor("(I)V"), ACC_PUBLIC));

        ClassDefinition roundTrippedDefinition = roundTrip(definition).definitions().getFirst();

        assertTrue(roundTrippedDefinition.getMethod("plain", "(I)V").getParameterAnnotations().isEmpty(),
                "a method with no parameter annotations should not gain empty ones");
    }

    private static ClassDefinition newDefinition() {
        return new ClassDefinition(
                Types.instanceTypeFromInternalName("example/Outer$Inner"),
                Types.instanceType(Object.class),
                ACC_PUBLIC
        );
    }

    private static DexFile roundTrip(ClassDefinition definition) throws Exception {
        DexFile dexFile = new DexFile(39, List.of(definition));
        DexHeader header = DexFile.CODEC.unmap(dexFile, new DexMapBuilder());

        Output output = Output.wrap();
        DexHeader.CODEC.write(header, output);
        DexHeader roundTrippedHeader = DexHeader.CODEC.read(Input.wrap(output.buffer()));
        return DexFile.CODEC.map(roundTrippedHeader, roundTrippedHeader.map());
    }

    private static boolean hasAnnotation(ClassDefinition definition, String type) {
        return definition.getAnnotations().stream()
                .anyMatch(annotation -> type.equals(annotation.annotation().type().internalName()));
    }

    private static boolean hasAnnotation(MethodMember method, String type) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> type.equals(annotation.annotation().type().internalName()));
    }
}
