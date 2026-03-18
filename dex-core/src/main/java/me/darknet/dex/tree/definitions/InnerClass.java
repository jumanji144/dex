package me.darknet.dex.tree.definitions;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wrapper for inner class metadata.
 * The different fields will represent different parts of the following class relation:
 * <pre>{@code
 *  class Apple { class Worm {} }
 * }</pre>
 *
 * @param innerClassName
 * 		The <b>full</b> internal name of an inner class. For example: {@code Apple$Worm}
 * @param outerClassName
 * 		The internal name of the class to which the inner class belongs. For example: {@code Apple}
 * @param innerName
 * 		The <i>(simple)</i> name of the inner class inside its enclosing class. For example: {@code Worm}
 * @param access
 * 		Defined access flags for the inner class.
 */
public record InnerClass(@NotNull String innerClassName, @NotNull String outerClassName,
                         @Nullable String innerName, int access) {
	/**
	 * @return {@code true} when the inner name is {@code null}, which indicates that the inner class is anonymous.
	 */
	public boolean anonymous() {
		return innerName == null;
	}
}
