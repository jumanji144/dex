package me.darknet.dex.tree.codec.definition;

import me.darknet.dex.collections.ImmutableCollections;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.EncodedField;
import me.darknet.dex.file.EncodedMethod;
import me.darknet.dex.file.annotation.FieldAnnotation;
import me.darknet.dex.file.annotation.MethodAnnotation;
import me.darknet.dex.file.annotation.ParameterAnnotation;
import me.darknet.dex.file.items.*;
import me.darknet.dex.file.value.Value;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.definitions.*;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationMap;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.constant.*;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ClassDefinitionCodec implements TreeCodec<ClassDefinition, ClassDefItem> {

    private static void processAttribute(@NotNull ClassDefinition definition, @NotNull AnnotationPart anno) {
        switch (anno.type().internalName()) {
            case "dalvik/annotation/EnclosingClass" -> {
                var enclosingClass = anno.element("value");
                if (enclosingClass instanceof TypeConstant(Type t) && t instanceof InstanceType it) {
                    definition.setEnclosingClass(it);
                } else {
                    throw new IllegalStateException("Invalid EnclosingClass annotation value");
                }
            }
            // TODO: EnclosingMethod
            case "dalvik/annotation/InnerClass" -> {
                var name = anno.element("name");
                var access = anno.element("accessFlags");

                if (access instanceof IntConstant(int a)) {
                    if (name instanceof StringConstant(String n))
                        definition.addInnerClass(new InnerClass(n, a));
                    else if (name instanceof NullConstant)
                        definition.addInnerClass(new InnerClass(null, a));
                } else {
                    throw new IllegalStateException("Invalid InnerClass annotation value");
                }
            }
            case "dalvik/annotation/Signature" -> {
                var element = anno.element("value");
                if (element instanceof StringConstant(String value)) {
                    definition.setSignature(value);
                } else  if (element instanceof ArrayConstant array) {
                    StringBuilder sb = new StringBuilder();
                    for (Constant constant : array.constants()) {
                        if (constant instanceof StringConstant(String value))
                            sb.append(value);
                    }
                    definition.setSignature(sb.toString());
                } else {
                    throw new IllegalStateException("Invalid Signature annotation value");
                }
            }
            case "dalvik/annotation/MemberClasses" -> {
                var classes = anno.element("value");
                if (classes instanceof ArrayConstant(List<Constant> constants)) {
                    for (Constant constant : constants) {
                        if (constant instanceof TypeConstant(Type t) && t instanceof InstanceType it) {
                            definition.addMemberClass(it);
                        } else {
                            throw new IllegalStateException("Invalid MemberClasses annotation value");
                        }
                    }
                } else {
                    throw new IllegalStateException("Invalid MemberClasses annotation value");
                }
            }
        }
    }

    private void processAttributes(@NotNull ClassDefinition definition) {
        // map attributes
        for (Annotation annotation : definition.getAnnotations()) {
            if (annotation.visibility() == Annotation.VISIBILITY_SYSTEM) {
                var anno = annotation.annotation();
                processAttribute(definition, anno);
            }
        }
    }

    @Override
    public @NotNull ClassDefinition map(@NotNull ClassDefItem input, @NotNull DexMap context) {
        InstanceType type = Types.instanceType(input.type());
        InstanceType superClass = input.superType() == null ? null : Types.instanceType(input.superType());
        List<InstanceType> interfaces = Types.instanceTypes(input.interfaces());
        ClassDefinition definition = new ClassDefinition(type, superClass, input.access());
        definition.setSourceFile(input.sourceFile() == null ? null : input.sourceFile().string());
        definition.addInterfaces(interfaces);

        ClassDataItem data = input.classData();

        if (data == null) // abstract method
            return definition;

        AnnotationsDirectoryItem directory = input.directory();
        AnnotationMap annotations = new AnnotationMap(
                new HashMap<>(16), new HashMap<>(16), new HashMap<>(16));

        if (directory != null) {
            for (FieldAnnotation fieldAnnotation : directory.fieldAnnotations()) {
                annotations.fieldAnnotations().put(fieldAnnotation.field(), fieldAnnotation.annotations());
            }
            for (MethodAnnotation methodAnnotation : directory.methodAnnotations()) {
                annotations.methodAnnotations().put(methodAnnotation.method(), methodAnnotation.annotations());
            }
            for (ParameterAnnotation parameterAnnotation : directory.parameterAnnotations()) {
                annotations.parameterAnnotations().put(parameterAnnotation.method(),
                        parameterAnnotation.annotations().items());
            }

            // map class annotations
            for (AnnotationOffItem entry : directory.classAnnotations().entries()) {
                definition.addAnnotation(Annotation.CODEC.map(entry.item(), context));
            }

        }

        List<Value> backingValues = input.staticValues() == null
                ? ImmutableCollections.emptyList(data.staticFields().size())
                : input.staticValues().values();
        int valueIndex = 0;
        for (EncodedField staticField : data.staticFields()) {
            FieldMember field = FieldMember.CODEC.map(staticField, annotations, context);
            int indexSnapshot = valueIndex++;
            Value value = indexSnapshot >= backingValues.size() ? null : backingValues.get(indexSnapshot);
            if (value == null) {
                // select default value
                field.setStaticValue(Constant.defaultValue(field.getType()));
            } else {
                // use encoded value
                field.setStaticValue(Constant.CODEC.map(value, context));
            }
            definition.putField(field);
        }

        for (EncodedField instanceField : data.instanceFields()) {
            FieldMember field = FieldMember.CODEC.map(instanceField, annotations, context);
            definition.putField(field);
        }

        for (EncodedMethod directMethod : data.directMethods()) {
            MethodMember method = MethodMember.CODEC.map(directMethod, annotations, context);
            definition.putMethod(method);
        }

        for (EncodedMethod virtualMethod : data.virtualMethods()) {
            MethodMember method = MethodMember.CODEC.map(virtualMethod, annotations, context);
            definition.putMethod(method);
        }

        processAttributes(definition);

        return definition;
    }

    @Override
    public @NotNull ClassDefItem unmap(@NotNull ClassDefinition output, @NotNull DexMapBuilder context) {
        TypeItem type = context.type(output.getType());

        TypeItem superType = output.getSuperClass() == null ? null : context.type(output.getSuperClass());
        TypeListItem interfaces = context.typeList(output.getInterfaces());

        StringItem sourceFile = output.getSourceFile() == null ? null : context.string(output.getSourceFile());

        AnnotationSetItem classAnnotations;
        if (!output.getAnnotations().isEmpty()) {
            classAnnotations = context.annotationSet(output.getAnnotations());
        } else {
            classAnnotations = new AnnotationSetItem(new ArrayList<>(0));
        }

        AnnotationMap annotations = new AnnotationMap(
                new HashMap<>(16), new HashMap<>(16), new HashMap<>(16));

        List<EncodedField> staticFields = new ArrayList<>();
        List<EncodedField> instanceFields = new ArrayList<>();

        List<EncodedMethod> directMethods = new ArrayList<>();
        List<EncodedMethod> virtualMethods = new ArrayList<>();

        List<Value> staticValues = new ArrayList<>();

        for (FieldMember value : output.getFields().values()) {
            EncodedField field = FieldMember.CODEC.unmap(value, annotations, context);
            if (value.getStaticValue() != null) {
                Value staticValue = Constant.CODEC.unmap(value.getStaticValue(), context);
                staticValues.add(staticValue);
            }
            if ((value.getAccess() & AccessFlags.ACC_STATIC) != 0) {
                staticFields.add(field);
            } else {
                instanceFields.add(field);
            }
        }

        for (MethodMember value : output.getMethods().values()) {
            EncodedMethod method = MethodMember.CODEC.unmap(value, annotations, context);
            if ((value.getAccess() & AccessFlags.ACC_STATIC) != 0) {
                directMethods.add(method);
            } else {
                virtualMethods.add(method);
            }
        }

        ClassDataItem data = null;
        if (!staticFields.isEmpty() || !instanceFields.isEmpty() || !directMethods.isEmpty()
                || !virtualMethods.isEmpty())
            data = new ClassDataItem(staticFields, instanceFields, directMethods, virtualMethods);

        if (data != null)
            context.classDatas().add(data);

        EncodedArrayItem staticValuesItem = staticValues.isEmpty() ? null : new EncodedArrayItem(staticValues);
        if (staticValuesItem != null)
            context.encodedArrays().add(staticValuesItem);

        List<FieldAnnotation> fieldAnnotations = new ArrayList<>();
        List<MethodAnnotation> methodAnnotations = new ArrayList<>();
        List<ParameterAnnotation> parameterAnnotations = new ArrayList<>();

        for (var entry : annotations.fieldAnnotations().entrySet()) {
            context.annotationSets().add(entry.getValue());
            fieldAnnotations.add(new FieldAnnotation(entry.getKey(), entry.getValue()));
        }

        for (var entry : annotations.methodAnnotations().entrySet()) {
            context.annotationSets().add(entry.getValue());
            methodAnnotations.add(new MethodAnnotation(entry.getKey(), entry.getValue()));
        }

        for (var entry : annotations.parameterAnnotations().entrySet()) {
            AnnotationSetRefList list = new AnnotationSetRefList(entry.getValue());
            context.annotationSetRefLists().add(list);
            parameterAnnotations.add(new ParameterAnnotation(entry.getKey(), list));
        }

        AnnotationsDirectoryItem directory = null;

        if (!fieldAnnotations.isEmpty() || !methodAnnotations.isEmpty() || !parameterAnnotations.isEmpty()) {
            directory = new AnnotationsDirectoryItem(classAnnotations, fieldAnnotations, methodAnnotations, parameterAnnotations);
        }

        if (directory != null)
            context.annotationsDirectories().add(directory);

        return new ClassDefItem(type, output.getAccess(), superType, interfaces, sourceFile, directory,
                data, staticValuesItem);
    }

}
