package me.darknet.dex.tree.remap;

import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.PrimitiveType;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Utility class for mapping types and member identifiers using a {@link DefinitionRemapper}.
 */
public class TypeMapping {
	private TypeMapping() {
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param type
	 * 		Type to map.
	 *
	 * @return Mapped type.
	 */
	public static @NotNull Type mapType(@NotNull DefinitionRemapper remapper, @NotNull Type type) {
		return switch (type) {
			case MethodType methodType -> mapMethodType(remapper, methodType);
			case ClassType classType -> mapClassType(remapper, classType);
		};
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param type
	 * 		Type to map.
	 *
	 * @return Mapped type.
	 */
	public static @NotNull ReferenceType mapReferenceType(@NotNull DefinitionRemapper remapper, @NotNull ReferenceType type) {
		return switch (type) {
			case InstanceType instanceType -> mapInstanceType(remapper, instanceType);
			case ArrayType arrayType -> (ReferenceType) mapClassType(remapper, arrayType);
		};
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param type
	 * 		Type to map.
	 *
	 * @return Mapped type.
	 */
	public static @NotNull InstanceType mapInstanceType(@NotNull DefinitionRemapper remapper, @NotNull InstanceType type) {
		String internalName = Objects.requireNonNull(remapper.mapInternalName(type.internalName()), "mapInternalName must not return null");
		return Types.instanceTypeFromInternalName(internalName);
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param type
	 * 		Type to map.
	 *
	 * @return Mapped type.
	 */
	public static @NotNull ClassType mapClassType(@NotNull DefinitionRemapper remapper, @NotNull ClassType type) {
		return switch (type) {
			case PrimitiveType primitiveType ->
					new PrimitiveType(primitiveType.descriptor(), primitiveType.kind(), primitiveType.name());
			case InstanceType instanceType -> mapInstanceType(remapper, instanceType);
			case ArrayType arrayType -> new ArrayType(mapClassType(remapper, arrayType.componentType()));
		};
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param type
	 * 		Type to map.
	 *
	 * @return Mapped type.
	 */
	public static @NotNull MethodType mapMethodType(@NotNull DefinitionRemapper remapper, @NotNull MethodType type) {
		StringBuilder builder = new StringBuilder();
		builder.append('(');
		for (ClassType parameterType : type.parameterTypes())
			builder.append(mapClassType(remapper, parameterType).descriptor());
		builder.append(')').append(mapClassType(remapper, type.returnType()).descriptor());
		return Types.methodTypeFromDescriptor(builder.toString());
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param types
	 * 		Types to map.
	 *
	 * @return Mapped types.
	 */
	public static @NotNull List<InstanceType> mapInstanceTypes(@NotNull DefinitionRemapper remapper,
	                                                           @NotNull List<InstanceType> types) {
		List<InstanceType> mapped = new ArrayList<>(types.size());
		for (InstanceType type : types)
			mapped.add(mapInstanceType(remapper, type));
		return mapped;
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param owner
	 * 		Owner type of the field.
	 * @param name
	 * 		Name of the field.
	 * @param type
	 * 		Type of the field.
	 *
	 * @return Mapped field name.
	 */
	public static @NotNull String mapFieldName(@NotNull DefinitionRemapper remapper, @NotNull InstanceType owner,
	                                           @NotNull String name, @NotNull ClassType type) {
		return Objects.requireNonNull(remapper.mapFieldName(owner, name, type), "mapFieldName must not return null");
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param owner
	 * 		Owner type of the method.
	 * @param name
	 * 		Name of the method.
	 * @param type
	 * 		Type of the method.
	 *
	 * @return Mapped method name, or the original name if it's a special method (constructor, static initializer).
	 */
	public static @NotNull String mapMethodName(@NotNull DefinitionRemapper remapper, @NotNull ReferenceType owner,
	                                            @NotNull String name, @NotNull MethodType type) {
		if (isSpecialMethodName(name)) {
			return name;
		}
		return Objects.requireNonNull(remapper.mapMethodName(owner, name, type), "mapMethodName must not return null");
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param name
	 * 		Name of the method.
	 * @param type
	 * 		Type of the method.
	 *
	 * @return Mapped method name.
	 */
	public static @NotNull String mapDynamicMethodName(@NotNull DefinitionRemapper remapper, @NotNull String name,
	                                                   @NotNull MethodType type) {
		return Objects.requireNonNull(remapper.mapDynamicMethodName(name, type),
				"mapDynamicMethodName must not return null");
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param owner
	 * 		Owner type of the field.
	 * @param name
	 * 		Name of the field.
	 * @param type
	 * 		Type of the field.
	 *
	 * @return Mapped field identifier.
	 *
	 * @see #mapFieldName(DefinitionRemapper, InstanceType, String, ClassType)
	 */
	public static @NotNull MemberIdentifier mapFieldIdentifier(@NotNull DefinitionRemapper remapper,
	                                                           @NotNull InstanceType owner,
	                                                           @NotNull String name,
	                                                           @NotNull ClassType type) {
		return new MemberIdentifier(mapFieldName(remapper, owner, name, type), mapClassType(remapper, type));
	}

	/**
	 * @param remapper
	 * 		Mapping implementation.
	 * @param owner
	 * 		Owner type of the method.
	 * @param name
	 * 		Name of the method.
	 * @param type
	 * 		Type of the method.
	 *
	 * @return Mapped method identifier.
	 *
	 * @see #mapMethodName(DefinitionRemapper, ReferenceType, String, MethodType)1
	 */
	public static @NotNull MemberIdentifier mapMethodIdentifier(@NotNull DefinitionRemapper remapper,
	                                                            @NotNull ReferenceType owner,
	                                                            @NotNull String name,
	                                                            @NotNull MethodType type) {
		return new MemberIdentifier(mapMethodName(remapper, owner, name, type), mapMethodType(remapper, type));
	}

	/**
	 * @param name
	 * 		Name of the method.
	 *
	 * @return {@code true} for special method names, like constructors ({@code <init>}) and static initializers ({@code <clinit>}).
	 */
	public static boolean isSpecialMethodName(@NotNull String name) {
		return !name.isEmpty() && name.charAt(0) == '<';
	}
}
