package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.convert.ir.value.IrType;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class IrTypeResolverTest {
    @Test
    void dexResolverProvidesSuperclassAndInterfaces() {
        InstanceType base = Types.instanceTypeFromInternalName("sample/Base");
        InstanceType child = Types.instanceTypeFromInternalName("sample/Child");
        InstanceType marker = Types.instanceTypeFromInternalName("sample/Marker");
        ClassDefinition baseDefinition = new ClassDefinition(base, Types.OBJECT, 0);
        ClassDefinition childDefinition = new ClassDefinition(child, base, 0);
        childDefinition.addInterface(marker);

        IrTypeHierarchyNode node = new DexIrTypeResolver(List.of(baseDefinition, childDefinition)).describe(child);
        assertNotNull(node);
        assertEquals(base, node.superType());
        assertEquals(List.of(marker), node.interfaces());
    }

    @Test
    void reflectionResolverDescribesLoadableTypesAndArrays() {
        ReflectionIrTypeResolver resolver = new ReflectionIrTypeResolver(getClass().getClassLoader());
        IrTypeHierarchyNode string = resolver.describe(Types.instanceType(String.class));
        assertNotNull(string);
        assertEquals(Types.instanceType(Object.class), string.superType());

        ArrayType strings = new ArrayType(Types.instanceType(String.class));
        IrTypeHierarchyNode array = resolver.describe(strings);
        assertNotNull(array);
        assertEquals(strings, array.type());
        assertNull(array.superType());
    }

    @Test
    void compositePrefersDexAndLeavesUnknownUnresolved() {
        InstanceType type = Types.instanceTypeFromInternalName("sample/Type");
        InstanceType dexParent = Types.instanceTypeFromInternalName("sample/DexParent");
        InstanceType reflectionParent = Types.instanceType(Object.class);
        IrTypeResolver first = ignored -> new IrTypeHierarchyNode(type, dexParent, List.of(), false);
        IrTypeResolver second = ignored -> new IrTypeHierarchyNode(type, reflectionParent, List.of(), false);
        CompositeIrTypeResolver composite = new CompositeIrTypeResolver(first, second);

        assertEquals(dexParent, composite.describe(type).superType());
        assertNull(new CompositeIrTypeResolver(IrTypeResolver.EMPTY).describe(type));
    }

    @Test
    void hierarchyAwareJoinFindsProvenCommonParent() {
        InstanceType parent = Types.instanceTypeFromInternalName("sample/Parent");
        InstanceType left = Types.instanceTypeFromInternalName("sample/Left");
        InstanceType right = Types.instanceTypeFromInternalName("sample/Right");
        IrTypeResolver resolver = type -> {
            if (type.equals(left) || type.equals(right)) return new IrTypeHierarchyNode(type, parent, List.of(), false);
            if (type.equals(parent)) return new IrTypeHierarchyNode(parent, Types.OBJECT, List.of(), false);
            return null;
        };

        assertEquals(parent, IrType.join(IrType.from(left), IrType.from(right), resolver).exactReference());
    }
}
