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
import me.darknet.dex.tree.definitions.constant.AnnotationConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.definitions.constant.TypeConstant;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void preservesUnsupportedClassSystemAnnotationsAsRawAnnotations() throws Exception {
        ClassDefinition definition = newDefinition();
        definition.addAnnotation(new Annotation((byte) Annotation.VISIBILITY_SYSTEM, new AnnotationPart(
                Types.instanceTypeFromInternalName("dalvik/annotation/NestHost"),
                Map.of("value", new TypeConstant(Types.instanceTypeFromInternalName("example/Outer")))
        )));

        ClassDefinition roundTrippedDefinition = roundTrip(definition).definitions().getFirst();

        assertTrue(hasAnnotation(roundTrippedDefinition, "dalvik/annotation/NestHost"));
    }

    @Test
    void consumesAndReemitsSupportedMethodMetadataWhilePreservingUnsupportedRawAnnotations() throws Exception {
        ClassDefinition definition = newDefinition();
        MethodMember method = new MethodMember("work", Types.methodTypeFromDescriptor("(Ljava/lang/String;I)V"), ACC_PUBLIC);
        method.setSignature("(Ljava/lang/String;I)V");
        method.setThrownTypes(List.of("java/io/IOException"));
        method.setParameterNames(Arrays.asList("name", null));
        method.setParameterAccessFlags(List.of(ACC_FINAL, 0));
        method.addAnnotation(new Annotation((byte) Annotation.VISIBILITY_SYSTEM, new AnnotationPart(
                Types.instanceTypeFromInternalName("dalvik/annotation/AnnotationDefault"),
                Map.of("value", new AnnotationConstant(new AnnotationPart(
                        Types.instanceTypeFromInternalName("example/Defaults"),
                        Map.of("value", new StringConstant("x"))
                )))
        )));
        definition.putMethod(method);

        MethodMember roundTrippedMethod = roundTrip(definition).definitions().getFirst().getMethods().values().iterator().next();

        assertEquals(List.of("java/io/IOException"), roundTrippedMethod.getThrownTypes());
        assertEquals(Arrays.asList("name", null), roundTrippedMethod.getParameterNames());
        assertEquals(List.of(ACC_FINAL, 0), roundTrippedMethod.getParameterAccessFlags());
        assertTrue(hasAnnotation(roundTrippedMethod, "dalvik/annotation/AnnotationDefault"));
        assertFalse(hasAnnotation(roundTrippedMethod, "dalvik/annotation/Throws"));
        assertFalse(hasAnnotation(roundTrippedMethod, "dalvik/annotation/MethodParameters"));
        assertFalse(hasAnnotation(roundTrippedMethod, "dalvik/annotation/Signature"));
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
