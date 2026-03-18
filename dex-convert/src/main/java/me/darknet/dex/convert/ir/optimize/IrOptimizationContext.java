package me.darknet.dex.convert.ir.optimize;

import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared optimization context for a single conversion session.
 */
public final class IrOptimizationContext {
	private final @NotNull ScopeKind scopeKind;
	private final @NotNull List<ClassDefinition> classes;
	private final @NotNull List<IrMethod> methods;
	private final @NotNull Map<String, List<IrMethod>> methodsByClass;
	private final @NotNull Map<String, IrMethod> methodsByIdentifier;

	public IrOptimizationContext(@NotNull ScopeKind scopeKind,
	                             @NotNull List<ClassDefinition> classes,
	                             @NotNull Map<ClassDefinition, List<IrMethod>> methodsByClass) {
		this.scopeKind = Objects.requireNonNull(scopeKind);
		this.classes = List.copyOf(classes);

		List<IrMethod> collectedMethods = new ArrayList<>();
		Map<String, List<IrMethod>> groupedMethods = new HashMap<>();
		Map<String, IrMethod> indexedMethods = new HashMap<>();
		for (ClassDefinition cls : this.classes) {
			List<IrMethod> classMethods = List.copyOf(methodsByClass.getOrDefault(cls, List.of()));
			String clsName = cls.getType().internalName();
			groupedMethods.put(clsName, classMethods);
			collectedMethods.addAll(classMethods);
			for (IrMethod method : classMethods) {
				InstanceType owner = method.source().getOwner();
				if (owner == null)
					throw new IllegalArgumentException("IR method " + method.source().getName() + " has no owner");
				MemberIdentifier identifier = method.source().getIdentifier();
				IrMethod previous = indexedMethods.put(methodKey(clsName, identifier.name(), identifier.descriptor()), method);
				if (previous != null && previous != method)
					throw new IllegalArgumentException("Duplicate IR method for " + owner.internalName() + "." + method.source().getIdentifier());
			}
		}

		this.methods = List.copyOf(collectedMethods);
		this.methodsByClass = Collections.unmodifiableMap(groupedMethods);
		this.methodsByIdentifier = Collections.unmodifiableMap(indexedMethods);
	}

	public @NotNull ScopeKind scopeKind() {
		return scopeKind;
	}

	public @NotNull List<ClassDefinition> classes() {
		return classes;
	}

	public @NotNull List<IrMethod> methods() {
		return methods;
	}

	public @NotNull List<IrMethod> getMethods(@NotNull InstanceType cls) {
		return methodsByClass.getOrDefault(cls.internalName(), Collections.emptyList());
	}

	public @Nullable IrMethod getMethod(@NotNull InstanceType owner, @NotNull MemberIdentifier identifier) {
		return getMethod(owner, identifier.name(), identifier.descriptor());
	}

	public @Nullable IrMethod getMethod(@NotNull InstanceType owner,  @NotNull String name, @NotNull String desc) {
		return getMethod(owner.internalName(), name, desc);
	}

	public @Nullable IrMethod getMethod(@NotNull String owner, @NotNull String name, @NotNull String descriptor) {
		return methodsByIdentifier.get(methodKey(owner, name, descriptor));
	}

	private static @NotNull String methodKey(@NotNull String owner, @NotNull String name, @NotNull String desc) {
		return owner + "." + name + desc;
	}

	public enum ScopeKind {
		WHOLE_DEX,
		SINGLE_CLASS
	}
}
