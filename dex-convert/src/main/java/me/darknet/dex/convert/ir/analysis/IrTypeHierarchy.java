package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.PrimitiveType;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Deterministic hierarchy operations shared by the lattice and analyses. */
public final class IrTypeHierarchy {
	private IrTypeHierarchy() {}

	/**
	 * Returns whether a proven reference value can be passed to a use expecting
	 * the supplied reference type. Unresolved application relationships return
	 * false, while array covariance and the JVM's array marker interfaces are
	 * handled without guessing hierarchy data.
	 */
	public static boolean isAssignable(@NotNull ReferenceType actual,
	                                   @NotNull ReferenceType expected,
	                                   @NotNull IrTypeResolver resolver) {
		if (actual.equals(expected)) return true;
		if (expected.equals(Types.OBJECT)) return true;
		if (actual instanceof ArrayType actualArray) {
			if (expected instanceof ArrayType expectedArray) {
				ClassType actualComponent = actualArray.componentType();
				ClassType expectedComponent = expectedArray.componentType();
				if (actualComponent instanceof PrimitiveType || expectedComponent instanceof PrimitiveType)
					return actualComponent.equals(expectedComponent);
				return actualComponent instanceof ReferenceType actualReference
						&& expectedComponent instanceof ReferenceType expectedReference
						&& isAssignable(actualReference, expectedReference, resolver);
			}
			return expected.equals(Types.OBJECT)
					|| expected.equals(Types.instanceType(Cloneable.class))
					|| expected.equals(Types.instanceType(Serializable.class));
		}
		if (!(actual instanceof InstanceType actualInstance) || !(expected instanceof InstanceType expectedInstance))
			return false;
		ArrayDeque<InstanceType> work = new ArrayDeque<>();
		Set<InstanceType> visited = new HashSet<>();
		work.add(actualInstance);
		while (!work.isEmpty()) {
			InstanceType current = work.removeFirst();
			if (!visited.add(current)) continue;
			if (current.equals(expectedInstance)) return true;
			IrTypeHierarchyNode node = resolver.describe(current);
			if (node == null) continue;
			if (node.superType() != null) work.add(node.superType());
			work.addAll(node.interfaces());
		}
		return false;
	}

	public static @NotNull ClassType commonSupertype(@NotNull ReferenceType left,
	                                                  @NotNull ReferenceType right,
	                                                  @NotNull IrTypeResolver resolver) {
		if (left.equals(right)) return left;
		if (left instanceof ArrayType leftArray && right instanceof ArrayType rightArray) {
			ClassType leftComponent = leftArray.componentType();
			ClassType rightComponent = rightArray.componentType();
			if (leftComponent instanceof PrimitiveType || rightComponent instanceof PrimitiveType)
				return leftComponent.equals(rightComponent) ? left : Types.OBJECT;
			if (leftComponent instanceof ReferenceType leftReference
					&& rightComponent instanceof ReferenceType rightReference) {
				ClassType component = commonSupertype(leftReference, rightReference, resolver);
				return component instanceof ReferenceType ? new ArrayType(component) : Types.OBJECT;
			}
			return Types.OBJECT;
		}
		if (left instanceof ArrayType || right instanceof ArrayType) {
			ReferenceType other = left instanceof ArrayType ? right : left;
			if (other.equals(Types.OBJECT)
					|| other.equals(Types.instanceType(Cloneable.class))
					|| other.equals(Types.instanceType(Serializable.class))) return other;
			return Types.OBJECT;
		}
		if (!(left instanceof InstanceType leftInstance) || !(right instanceof InstanceType rightInstance))
			return Types.OBJECT;
		if (isAssignable(leftInstance, rightInstance, resolver)) return rightInstance;
		if (isAssignable(rightInstance, leftInstance, resolver)) return leftInstance;

		Map<InstanceType, Integer> leftAncestors = ancestors(leftInstance, resolver);
		Map<InstanceType, Integer> rightAncestors = ancestors(rightInstance, resolver);
		return leftAncestors.keySet().stream()
				.filter(rightAncestors::containsKey)
				.max(Comparator.comparingInt((InstanceType type) -> specificity(type, resolver))
					.thenComparingInt(type -> -Math.max(leftAncestors.get(type), rightAncestors.get(type)))
					.thenComparingInt(type -> -(leftAncestors.get(type) + rightAncestors.get(type)))
					.thenComparing(InstanceType::descriptor))
				.orElse(Types.OBJECT);
	}

	private static int specificity(@NotNull InstanceType type, @NotNull IrTypeResolver resolver) {
		if (type.equals(Types.OBJECT)) return 0;
		IrTypeHierarchyNode node = resolver.describe(type);
		return node != null && node.interfaceType() ? 1 : 2;
	}

	private static @NotNull Map<InstanceType, Integer> ancestors(@NotNull InstanceType start,
	                                                               @NotNull IrTypeResolver resolver) {
		Map<InstanceType, Integer> distances = new HashMap<>();
		ArrayDeque<InstanceType> queue = new ArrayDeque<>();
		distances.put(start, 0);
		queue.add(start);
		while (!queue.isEmpty()) {
			InstanceType current = queue.removeFirst();
			int distance = distances.get(current) + 1;
			IrTypeHierarchyNode node = resolver.describe(current);
			if (node == null) continue;
			if (node.superType() != null) add(node.superType(), distance, distances, queue);
			for (InstanceType interfaceType : node.interfaces()) add(interfaceType, distance, distances, queue);
		}
		// Every reference is assignable to Object, even when external hierarchy
		// metadata is unavailable.
		distances.putIfAbsent(Types.OBJECT, Integer.MAX_VALUE / 4);
		return distances;
	}

	private static void add(@NotNull InstanceType type, int distance,
	                        @NotNull Map<InstanceType, Integer> distances,
	                        @NotNull ArrayDeque<InstanceType> queue) {
		if (distances.putIfAbsent(type, distance) == null) queue.add(type);
	}
}
