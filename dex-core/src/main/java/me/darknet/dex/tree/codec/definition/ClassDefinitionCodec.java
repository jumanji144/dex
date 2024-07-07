package me.darknet.dex.tree.codec.definition;

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
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.annotation.AnnotationMap;
import me.darknet.dex.tree.definitions.constant.*;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class ClassDefinitionCodec implements TreeCodec<ClassDefinition, ClassDefItem> {

    @Override
    public ClassDefinition map(ClassDefItem input, DexMap context) {
        InstanceType type = Types.instanceType(input.type());
        InstanceType superClass = input.superType() == null ? null : Types.instanceType(input.superType());
        List<InstanceType> interfaces = Types.instanceTypes(input.interfaces());
        ClassDefinition definition = new ClassDefinition(type, input.access(), superClass);
        definition.sourceFile(input.sourceFile() == null ? null : input.sourceFile().string());
        definition.interfaces(interfaces);

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
        }

        List<Value> backingValues = input.staticValues() == null ? Collections.emptyList() : input.staticValues().values();
        int valueIndex = 0;
        for (EncodedField staticField : data.staticFields()) {
            FieldMember field = FieldMember.CODEC.map(staticField, annotations, context);
            Value value = backingValues.get(valueIndex++);
            if (value == null) {
                // select default value
                field.staticValue(Constant.defaultValue(field.type()));
            } else {
                // use encoded value
                field.staticValue(Constant.CODEC.map(value, context));
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

        return definition;
    }

    @Override
    public ClassDefItem unmap(ClassDefinition output, DexMapBuilder context) {
        return null;
    }

}
