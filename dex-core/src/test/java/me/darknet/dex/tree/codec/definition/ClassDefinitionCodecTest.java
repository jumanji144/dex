package me.darknet.dex.tree.codec.definition;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.items.ClassDataItem;
import me.darknet.dex.file.items.ClassDefItem;
import me.darknet.dex.file.items.MethodItem;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.AccessFlags;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClassDefinitionCodecTest implements AccessFlags {

    @Test
    void roundTripsClassAnnotationsWithoutClassData() throws Exception {
        ClassDefinition definition = new ClassDefinition(
                Types.instanceTypeFromInternalName("example/AnnotatedEmpty"),
                Types.instanceType(Object.class),
                ACC_PUBLIC
        );
        definition.addAnnotation(new Annotation((byte) Annotation.VISIBILITY_RUNTIME, new AnnotationPart(
                Types.instanceTypeFromInternalName("example/Anno"),
                Map.of()
        )));

        DexFile dexFile = new DexFile(39, List.of(definition));
        DexHeader header = DexFile.CODEC.unmap(dexFile, new DexMapBuilder());

        ClassDefItem classDefItem = header.map().classes().get(0);
        assertNotNull(classDefItem.directory(), "Class-only annotations should still emit an annotations directory");
        assertEquals(1, classDefItem.directory().classAnnotations().entries().size());

        Output output = Output.wrap();
        DexHeader.CODEC.write(header, output);
        DexHeader roundTrippedHeader = DexHeader.CODEC.read(Input.wrap(output.buffer()));
        DexFile roundTripped = DexFile.CODEC.map(roundTrippedHeader, roundTrippedHeader.map());

        assertEquals(dexFile, roundTripped);
    }

    @Test
    void partitionsDirectAndVirtualMethodsCorrectly() {
        ClassDefinition definition = new ClassDefinition(
                Types.instanceTypeFromInternalName("example/MethodKinds"),
                Types.instanceType(Object.class),
                ACC_PUBLIC
        );
        definition.putMethod(new MethodMember("<init>", Types.methodTypeFromDescriptor("()V"), ACC_PUBLIC));
        definition.putMethod(new MethodMember("<clinit>", Types.methodTypeFromDescriptor("()V"), ACC_STATIC));
        definition.putMethod(new MethodMember("privateInstance", Types.methodTypeFromDescriptor("()V"), ACC_PRIVATE));
        definition.putMethod(new MethodMember("staticMethod", Types.methodTypeFromDescriptor("()V"), ACC_STATIC));
        definition.putMethod(new MethodMember("virtualMethod", Types.methodTypeFromDescriptor("()V"), ACC_PUBLIC));

        ClassDefItem classDefItem = ClassDefinition.CODEC.unmap(definition, new DexMapBuilder());
        ClassDataItem classData = classDefItem.classData();
        assertNotNull(classData);
        assertEquals(4, classData.directMethods().size());
        assertEquals(1, classData.virtualMethods().size());
        assertEquals(List.of("<init>", "<clinit>", "privateInstance", "staticMethod"),
                classData.directMethods().stream().map(Encoded -> Encoded.method().name().string()).toList());
        assertEquals(List.of("virtualMethod"),
                classData.virtualMethods().stream().map(Encoded -> Encoded.method().name().string()).toList());
    }
}
