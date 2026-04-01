package me.darknet.dex.tree.remap;

import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.ReferenceType;
import org.jetbrains.annotations.NotNull;

/**
 * Callback API for remapping class, field, and method names in the dex tree model.
 */
public abstract class DefinitionRemapper {
	/**
	 * @param internalName
	 * 		Name of the class to remap.
	 *
	 * @return Mapped class name.
	 */
	public @NotNull String mapInternalName(@NotNull String internalName) {
		return internalName;
	}

	/**
	 * @param owner
	 * 		Owner type of the field.
	 * @param name
	 * 		Name of the field.
	 * @param type
	 * 		Type of the field.
	 *
	 * @return Mapped field name.
	 */
	public @NotNull String mapFieldName(@NotNull InstanceType owner, @NotNull String name, @NotNull ClassType type) {
		return name;
	}

	/**
	 * @param owner
	 * 		Owner type of the method.
	 * @param name
	 * 		Name of the method.
	 * @param type
	 * 		Type of the method.
	 *
	 * @return Mapped method name. Can be the original name for special methods (constructors, static initializers).
	 */
	public @NotNull String mapMethodName(@NotNull ReferenceType owner, @NotNull String name, @NotNull MethodType type) {
		return name;
	}

	/**
	 * @param name
	 * 		Name of the method.
	 * @param type
	 * 		Type of the method.
	 *
	 * @return Mapped method name.
	 */
	public @NotNull String mapDynamicMethodName(@NotNull String name, @NotNull MethodType type) {
		return name;
	}
}
