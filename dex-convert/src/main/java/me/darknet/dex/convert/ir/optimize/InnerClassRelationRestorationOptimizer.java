package me.darknet.dex.convert.ir.optimize;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.value.IrParameter;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.type.InstanceType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static me.darknet.dex.tree.definitions.AccessFlags.ACC_STATIC;
import static me.darknet.dex.tree.definitions.instructions.Invoke.DIRECT;

/**
 * Optimizer that restores missing inner/outer class metadata from naming and IR usage patterns.
 */
public final class InnerClassRelationRestorationOptimizer implements IrOptimizer {
	@Override
	public void optimizeProgram(@NotNull IrOptimizationContext context) {
		Map<String, ClassDefinition> classesByName = indexClasses(context);
		Map<String, CreationEvidence> creationEvidence = collectCreationEvidence(context);

		for (ClassDefinition cls : context.classes()) {
			NestedClassCandidate candidate = parseCandidate(cls, classesByName);
			if (candidate == null)
				continue;
			restoreEnclosingClass(cls, candidate);
			restoreInnerClassEntry(cls, candidate);
			restoreMemberClass(candidate);
			restoreEnclosingMethod(cls, candidate, creationEvidence.get(cls.getType().internalName()));
		}
	}

	private static @NotNull Map<String, ClassDefinition> indexClasses(@NotNull IrOptimizationContext context) {
		Map<String, ClassDefinition> classesByName = new HashMap<>(context.classes().size());
		for (ClassDefinition cls : context.classes())
			classesByName.put(cls.getType().internalName(), cls);
		return classesByName;
	}

	private static @NotNull Map<String, CreationEvidence> collectCreationEvidence(@NotNull IrOptimizationContext context) {
		Map<String, CreationEvidenceBuilder> builders = new HashMap<>();
		for (IrMethod method : context.methods()) {
			for (IrBlock block : method.blocks()) {
				for (IrStmt statement : block.statements()) {
					// Filter out non-constructor calls and non-invoke statements in a single step to avoid unnecessary casts.
					if (!(statement instanceof IrOp op))
						continue;
					if (!(op.payload() instanceof InvokeInstruction invoke) || !isConstructor(invoke))
						continue;
					if (!(invoke.owner() instanceof InstanceType owner))
						continue;

					// We have a constructor call. Record it as evidence that the constructed class is likely an inner class of the caller.
					CreationEvidenceBuilder builder = builders.computeIfAbsent(owner.internalName(), ignored -> new CreationEvidenceBuilder());
					builder.record(new CreationCall(
							method.source().getOwner().internalName(),
							method.source().getIdentifier(),
							firstExplicitArgumentIsCallerReceiver(method, op)));
				}
			}
		}

		Map<String, CreationEvidence> evidence = new HashMap<>(builders.size());
		for (Map.Entry<String, CreationEvidenceBuilder> entry : builders.entrySet())
			evidence.put(entry.getKey(), entry.getValue().build());
		return evidence;
	}

	private static @Nullable NestedClassCandidate parseCandidate(@NotNull ClassDefinition inner,
	                                                             @NotNull Map<String, ClassDefinition> classesByName) {
		String name = inner.getType().internalName();
		int boundary = name.lastIndexOf('$');
		if (boundary < 0)
			return null;

		String outerName = name.substring(0, boundary);
		ClassDefinition outer = classesByName.get(outerName);
		if (outer == null)
			return null;

		String nestedSegment = name.substring(boundary + 1);
		if (nestedSegment.isEmpty() || nestedSegment.indexOf('$') >= 0)
			return null;

		if (nestedSegment.chars().allMatch(Character::isDigit))
			return new NestedClassCandidate(inner, outer, NestedKind.ANONYMOUS, inner.getType().internalName());

		int digitPrefix = 0;
		while (digitPrefix < nestedSegment.length() && Character.isDigit(nestedSegment.charAt(digitPrefix)))
			digitPrefix++;

		if (digitPrefix > 0 && digitPrefix < nestedSegment.length())
			return new NestedClassCandidate(inner, outer, NestedKind.LOCAL, nestedSegment.substring(digitPrefix));
		return new NestedClassCandidate(inner, outer, NestedKind.MEMBER, nestedSegment);
	}

	private static void restoreEnclosingClass(@NotNull ClassDefinition inner, @NotNull NestedClassCandidate candidate) {
		if (inner.getEnclosingClass() == null)
			inner.setEnclosingClass(candidate.outer().getType());
	}

	private static void restoreInnerClassEntry(@NotNull ClassDefinition inner, @NotNull NestedClassCandidate candidate) {
		String outerClassName = candidate.outer().getType().internalName();
		String innerClassName = candidate.inner().getType().internalName();
		String innerName = candidate.kind() == NestedKind.ANONYMOUS ? null : candidate.innerSimpleName();
		InnerClass innerClass = new InnerClass(innerClassName, outerClassName, innerName, inner.getAccess());
		inner.addInnerClass(innerClass);
		candidate.outer().addInnerClass(innerClass);
	}

	private static void restoreMemberClass(@NotNull NestedClassCandidate candidate) {
		if (candidate.kind() != NestedKind.MEMBER)
			return;
		if (candidate.outer().getMemberClasses().contains(candidate.inner().getType()))
			return;
		candidate.outer().addMemberClass(candidate.inner().getType());
	}

	private static void restoreEnclosingMethod(@NotNull ClassDefinition inner,
	                                           @NotNull NestedClassCandidate candidate,
	                                           @Nullable CreationEvidence evidence) {
		if (inner.getEnclosingMethod() != null)
			return;
		if (candidate.kind() == NestedKind.MEMBER || evidence == null)
			return;
		if (!evidence.allCreatorsInOwner(candidate.outer().getType().internalName()))
			return;
		if (evidence.creatorMethods().size() != 1)
			return;

		MemberIdentifier creator = evidence.creatorMethods().iterator().next();
		if ("<clinit>".equals(creator.name()))
			return;

		inner.setEnclosingMethod(creator);
	}

	private static boolean isConstructor(@NotNull InvokeInstruction invoke) {
		return invoke.opcode() == DIRECT && "<init>".equals(invoke.name());
	}

	private static boolean firstExplicitArgumentIsCallerReceiver(@NotNull IrMethod caller, @NotNull IrOp op) {
		if (op.inputs().size() <= 1)
			return false;
		if ((caller.source().getAccess() & ACC_STATIC) != 0)
			return false;

		IrValue value = op.inputs().get(1).canonical();
		if (!(value instanceof IrParameter parameter))
			return false;

		int receiverRegister = caller.registerCount() - caller.source().getCode().getIn();
		return parameter.register() == receiverRegister && caller.source().getOwner().equals(parameter.type());
	}

	private enum NestedKind {
		MEMBER,
		LOCAL,
		ANONYMOUS
	}

	private record NestedClassCandidate(@NotNull ClassDefinition inner,
	                                    @NotNull ClassDefinition outer,
	                                    @NotNull NestedKind kind,
	                                    @NotNull String innerSimpleName) {}

	private record CreationCall(@NotNull String callerOwner,
	                            @NotNull MemberIdentifier callerMethod,
	                            boolean firstExplicitArgumentUsesCallerReceiver) {}

	private record CreationEvidence(@NotNull Set<String> creatorOwners,
	                                @NotNull Set<MemberIdentifier> creatorMethods,
	                                boolean firstExplicitArgumentUsesCallerReceiver) {
		private boolean allCreatorsInOwner(@NotNull String owner) {
			return !creatorOwners.isEmpty() && creatorOwners.stream().allMatch(owner::equals);
		}
	}

	private static final class CreationEvidenceBuilder {
		private final Set<String> creatorOwners = new HashSet<>();
		private final Set<MemberIdentifier> creatorMethods = new HashSet<>();
		private boolean firstExplicitArgumentUsesCallerReceiver;

		private void record(@NotNull CreationCall call) {
			creatorOwners.add(call.callerOwner());
			creatorMethods.add(call.callerMethod());
			firstExplicitArgumentUsesCallerReceiver |= call.firstExplicitArgumentUsesCallerReceiver();
		}

		private @NotNull CreationEvidence build() {
			return new CreationEvidence(Set.copyOf(creatorOwners), Set.copyOf(creatorMethods),
					firstExplicitArgumentUsesCallerReceiver);
		}
	}
}
