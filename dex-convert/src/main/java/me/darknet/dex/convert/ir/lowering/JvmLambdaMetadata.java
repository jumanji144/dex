package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.MoveResultInstruction;
import me.darknet.dex.tree.definitions.instructions.NopInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proof metadata extracted from DEX-generated synthetic lambda classes.
 * The lowering backend consumes this metadata only in guarded mode; missing
 * metadata deliberately means ordinary synthetic-class materialization.
 */
public final class JvmLambdaMetadata {
	public enum Kind { FUNCTION, COMPARATOR, RUNNABLE }

	public record Target(@NotNull Kind kind, @NotNull String owner, @NotNull String name,
	                     @NotNull String descriptor, int invokeKind, @NotNull String captureDescriptor) {
		public Target(@NotNull Kind kind, @NotNull String owner, @NotNull String name,
		              @NotNull String descriptor, int invokeKind) {
			this(kind, owner, name, descriptor, invokeKind, "");
		}
	}

	private static final JvmLambdaMetadata EMPTY = new JvmLambdaMetadata(Map.of());
	private final Map<String, Target> targets;

	private JvmLambdaMetadata(@NotNull Map<String, Target> targets) {
		this.targets = Map.copyOf(targets);
	}

	public static @NotNull JvmLambdaMetadata empty() {
		return EMPTY;
	}

	/** Builds metadata only for exact synthetic method-reference and runnable bodies. */
	public static @NotNull JvmLambdaMetadata fromDefinitions(@NotNull List<ClassDefinition> definitions) {
		Map<String, Target> targets = new HashMap<>();
		for (ClassDefinition definition : definitions) {
			String className = definition.getType().internalName();
			if (!className.contains("$$ExternalSyntheticLambda")) continue;
			for (MethodMember method : definition.getMethods().values()) {
				Kind kind = lambdaKind(method);
				if (kind == null || method.getCode() == null) continue;
				Target target = kind == Kind.RUNNABLE
						? parseRunnable(definition, method)
						: hasZeroCaptureConstructor(definition) ? parseBody(kind, method.getCode()) : null;
				if (target != null) targets.put(className, target);
			}
		}
		return targets.isEmpty() ? EMPTY : new JvmLambdaMetadata(targets);
	}

	public @Nullable Target target(@NotNull String syntheticClass) {
		return targets.get(syntheticClass);
	}

	private static boolean hasZeroCaptureConstructor(@NotNull ClassDefinition definition) {
		for (MethodMember method : definition.getMethods().values())
			if ("<init>".equals(method.getName()) && "()V".equals(method.getType().descriptor())) return true;
		return false;
	}

	private static @Nullable Kind lambdaKind(@NotNull MethodMember method) {
		if ("apply".equals(method.getName())
				&& "(Ljava/lang/Object;)Ljava/lang/Object;".equals(method.getType().descriptor()))
			return Kind.FUNCTION;
		if ("compare".equals(method.getName())
				&& "(Ljava/lang/Object;Ljava/lang/Object;)I".equals(method.getType().descriptor()))
			return Kind.COMPARATOR;
		if ("run".equals(method.getName()) && "()V".equals(method.getType().descriptor()))
			return Kind.RUNNABLE;
		return null;
	}

	private static @Nullable Target parseRunnable(@NotNull ClassDefinition definition,
	                                              @NotNull MethodMember run) {
		MethodMember constructor = definition.getMethods().values().stream()
				.filter(method -> "<init>".equals(method.getName()) && method.getCode() != null
						&& !method.getType().parameterTypes().isEmpty())
				.findFirst().orElse(null);
		if (constructor == null) return null;
		List<ClassType> captures = constructor.getType().parameterTypes();
		List<Instruction> executable = run.getCode().getInstructions().stream()
				.filter(instruction -> !(instruction instanceof Label)).toList();
		if (executable.size() != captures.size() + 2) return null;
		for (int index = 0; index < captures.size(); index++) {
			if (!(executable.get(index) instanceof InstanceFieldInstruction field)
					|| !definition.getType().internalName().equals(field.owner().internalName())
					|| !captures.get(index).descriptor().equals(field.type().descriptor())) return null;
		}
		if (!(executable.get(captures.size()) instanceof InvokeInstruction invoke)
				|| !(executable.get(captures.size() + 1) instanceof ReturnInstruction)
				|| invoke.opcode() == Invoke.STATIC || invoke.opcode() == Invoke.DIRECT
				|| !invoke.type().returnType().descriptor().equals("V")) return null;
		if (invoke.type().parameterTypes().size() + 1 != captures.size()) return null;
		for (int index = 0; index < invoke.type().parameterTypes().size(); index++)
			if (!invoke.type().parameterTypes().get(index).descriptor().equals(captures.get(index + 1).descriptor())) return null;
		StringBuilder captureDescriptor = new StringBuilder();
		for (ClassType capture : captures) captureDescriptor.append(capture.descriptor());
		return new Target(Kind.RUNNABLE, invoke.owner().internalName(), invoke.name(),
				invoke.type().descriptor(), invoke.opcode(), captureDescriptor.toString());
	}

	private static @Nullable Target parseBody(@NotNull Kind kind, @NotNull Code code) {
		List<Instruction> executable = code.getInstructions().stream()
				.filter(instruction -> !(instruction instanceof Label))
				.toList();
		int castCount = kind == Kind.FUNCTION ? 1 : 2;
		if (executable.size() < castCount + 3) return null;
		if (executable.subList(castCount + 3, executable.size()).stream()
				.anyMatch(instruction -> !(instruction instanceof NopInstruction))) return null;
		for (int index = 0; index < castCount; index++)
			if (!(executable.get(index) instanceof CheckCastInstruction)) return null;
		if (!(executable.get(castCount) instanceof InvokeInstruction invoke)
				|| !(executable.get(castCount + 1) instanceof MoveResultInstruction)
				|| !(executable.get(castCount + 2) instanceof ReturnInstruction)) return null;
		if (kind == Kind.COMPARATOR && invoke.opcode() != Invoke.STATIC) return null;
		if (kind == Kind.FUNCTION && invoke.opcode() == Invoke.STATIC) return null;
		return new Target(kind, invoke.owner().internalName(), invoke.name(), invoke.type().descriptor(), invoke.opcode());
	}
}
