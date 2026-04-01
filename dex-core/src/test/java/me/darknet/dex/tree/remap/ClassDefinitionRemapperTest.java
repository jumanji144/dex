package me.darknet.dex.tree.remap;

import me.darknet.dex.tree.definitions.AccessFlags;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.definitions.constant.ArrayConstant;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.EnumConstant;
import me.darknet.dex.tree.definitions.constant.Handle;
import me.darknet.dex.tree.definitions.constant.HandleConstant;
import me.darknet.dex.tree.definitions.constant.MemberConstant;
import me.darknet.dex.tree.definitions.constant.TypeConstant;
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstMethodHandleInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstMethodTypeInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstTypeInstruction;
import me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.SparseSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassDefinitionRemapperTest implements AccessFlags {
	private static final InstanceType OLD_OUTER = Types.instanceTypeFromInternalName("oldpkg/Outer");
	private static final InstanceType OLD_INNER = Types.instanceTypeFromInternalName("oldpkg/Outer$Inner");
	private static final InstanceType OLD_MEMBER = Types.instanceTypeFromInternalName("oldpkg/Outer$Inner$Member");
	private static final InstanceType OLD_MARKER = Types.instanceTypeFromInternalName("oldpkg/Marker");
	private static final InstanceType OLD_OWNER = Types.instanceTypeFromInternalName("oldpkg/Owner");
	private static final InstanceType OLD_ENUM = Types.instanceTypeFromInternalName("oldpkg/Enum");
	private static final InstanceType OLD_PROBLEM = Types.instanceTypeFromInternalName("oldpkg/Problem");
	private static final MethodType OLD_METHOD_TYPE =
			Types.methodTypeFromDescriptor("(Loldpkg/Outer$Inner;[Loldpkg/Outer$Inner;)Loldpkg/Outer$Inner;");
	private static final MethodType OLD_ENCLOSING_METHOD_TYPE =
			Types.methodTypeFromDescriptor("(Loldpkg/Outer$Inner;)Loldpkg/Outer$Inner;");
	private static final MethodType OLD_BOOTSTRAP_TYPE =
			Types.methodTypeFromDescriptor("(Loldpkg/Outer$Inner;)V");
	private static final String OLD_NESTED_SIGNATURE =
			"Loldpkg/Box<Loldpkg/Outer$Inner;>.Holder<Loldpkg/Outer$Inner;>;";

	@Test
	void identityRemapReturnsFreshCopy() {
		// Mapping a definition with an identity remapper should return a new instance with the same content, but different references.
		ClassDefinition source = sampleClass();
		ClassDefinition target = new ClassDefinitionRemapper(new DefinitionRemapper() {
			// no-op
		}).remap(source);

		assertNotSame(source, target);
		assertEquals(source.getType(), target.getType());
		assertEquals(source.getSignature(), target.getSignature());
		assertNotSame(source.getAnnotations().getFirst(), target.getAnnotations().getFirst());

		FieldMember sourceField = source.getFields().values().iterator().next();
		FieldMember targetField = target.getFields().values().iterator().next();
		assertNotSame(sourceField, targetField);
		assertNotSame(sourceField.getStaticValue(), targetField.getStaticValue());
		assertEquals(sourceField, targetField, "Field equality mis-match");

		MethodMember sourceMethod = source.getMethods().values().iterator().next();
		MethodMember targetMethod = target.getMethods().values().iterator().next();
		assertNotSame(sourceMethod, targetMethod);
		assertNotSame(sourceMethod.getCode(), targetMethod.getCode());
		assertNotSame(sourceMethod.getAnnotations().getFirst(), targetMethod.getAnnotations().getFirst());
		assertEquals(sourceMethod, targetMethod, "Method equality mis-match");

		Label sourceFirstLabel = (Label) sourceMethod.getCode().getInstructions().getFirst();
		Label targetFirstLabel = (Label) targetMethod.getCode().getInstructions().getFirst();
		assertNotSame(sourceFirstLabel, targetFirstLabel);
		assertEquals(sourceFirstLabel.position(), targetFirstLabel.position());
		assertEquals(sourceFirstLabel.lineNumber(), targetFirstLabel.lineNumber());

		InvokeInstruction sourceInvoke = findInstruction(sourceMethod.getCode(), InvokeInstruction.class);
		InvokeInstruction targetInvoke = findInstruction(targetMethod.getCode(), InvokeInstruction.class);
		assertNotSame(sourceInvoke, targetInvoke);
		assertEquals(sourceMethod.getCode().offsetOf(sourceInvoke), targetMethod.getCode().offsetOf(targetInvoke));

		DebugInformation.LocalVariable sourceLocal = sourceMethod.getCode().getDebugInfo().locals().getFirst();
		DebugInformation.LocalVariable targetLocal = targetMethod.getCode().getDebugInfo().locals().getFirst();
		assertNotSame(sourceLocal.start(), targetLocal.start());
		assertNotSame(sourceLocal.end(), targetLocal.end());
		assertEquals(sourceLocal.signature(), targetLocal.signature());

		// This will be the whole structure equality check.
		// This *should* be the same if we truly have made no mappings and just copied the structure.
		// There are some hacky bits in our code that rely on reference equality and those are emitted for now which isn't great...
		assertEquals(source, target, "Class equality mis-match");
	}

	@Test
	void remapsDeclarationsReferencesAndSignatures() {
		ClassDefinition source = sampleClass();
		ClassDefinition target = new ClassDefinitionRemapper(renamingRemapper()).remap(source);

		assertEquals("newpkg/RenamedOuter$RenamedInner", target.getType().internalName());
		assertEquals("newpkg/RenamedMarker", target.getInterfaces().getFirst().internalName());
		assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Object;Lnewpkg/RenamedBox<Lnewpkg/RenamedOuter$RenamedInner;>.RenamedHolder<Lnewpkg/RenamedOuter$RenamedInner;>;",
				target.getSignature());
		assertEquals("newpkg/RenamedOuter", target.getEnclosingClass().internalName());
		assertEquals("createRenamed", target.getEnclosingMethod().name());
		assertEquals("(Lnewpkg/RenamedOuter$RenamedInner;)Lnewpkg/RenamedOuter$RenamedInner;",
				target.getEnclosingMethod().descriptor());

		InnerClass innerClass = target.getInnerClasses().getFirst();
		assertEquals("newpkg/RenamedOuter$RenamedInner", innerClass.innerClassName());
		assertEquals("newpkg/RenamedOuter", innerClass.outerClassName());
		assertEquals("RenamedInner", innerClass.innerName());
		assertEquals("newpkg/RenamedOuter$RenamedInner$RenamedMember", target.getMemberClasses().getFirst().internalName());

		Annotation classAnnotation = target.getAnnotations().getFirst();
		assertEquals("newpkg/RenamedAnno", classAnnotation.annotation().type().internalName());
		assertInstanceOf(TypeConstant.class, classAnnotation.annotation().element("type"));
		assertEquals("newpkg/RenamedOuter$RenamedInner",
				((TypeConstant) classAnnotation.annotation().element("type")).type().descriptor().substring(1,
						((TypeConstant) classAnnotation.annotation().element("type")).type().descriptor().length() - 1));

		MemberConstant annotatedMember = (MemberConstant) classAnnotation.annotation().element("member");
		assertEquals("newpkg/RenamedOwner", annotatedMember.owner().internalName());
		assertEquals("renamedMethod", annotatedMember.member().name());
		assertEquals("(Lnewpkg/RenamedOuter$RenamedInner;)Lnewpkg/RenamedOuter$RenamedInner;",
				annotatedMember.member().descriptor());

		HandleConstant annotatedHandle = (HandleConstant) classAnnotation.annotation().element("handle");
		assertEquals("renamedMethod", annotatedHandle.handle().name());
		assertEquals("newpkg/RenamedOuter$RenamedInner", annotatedHandle.handle().owner().internalName());

		FieldMember field = target.getFields().values().iterator().next();
		assertEquals("renamedField", field.getName());
		assertEquals("newpkg/RenamedOuter$RenamedInner", ((InstanceType) field.getType()).internalName());
		assertEquals(OLD_NESTED_SIGNATURE.replace("oldpkg/Box", "newpkg/RenamedBox")
						.replace(".Holder", ".RenamedHolder")
						.replace("oldpkg/Outer$Inner", "newpkg/RenamedOuter$RenamedInner"),
				field.getSignature());
		assertEquals("newpkg/RenamedOuter$RenamedInner", ((TypeConstant) field.getStaticValue()).type().descriptor()
				.substring(1, ((TypeConstant) field.getStaticValue()).type().descriptor().length() - 1));
		assertEquals("newpkg/RenamedOuter$RenamedInner", field.getOwner().internalName());

		MethodMember method = target.getMethods().values().iterator().next();
		assertEquals("renamedMethod", method.getName());
		assertEquals("(Lnewpkg/RenamedOuter$RenamedInner;[Lnewpkg/RenamedOuter$RenamedInner;)Lnewpkg/RenamedOuter$RenamedInner;",
				method.getType().descriptor());
		assertEquals("<E:Ljava/lang/Object;>(Lnewpkg/RenamedOuter$RenamedInner;Lnewpkg/RenamedBox<Lnewpkg/RenamedOuter$RenamedInner;>.RenamedHolder<Lnewpkg/RenamedOuter$RenamedInner;>;)Lnewpkg/RenamedBox<Lnewpkg/RenamedOuter$RenamedInner;>.RenamedHolder<TE;>;^Lnewpkg/RenamedProblem;",
				method.getSignature());
		assertEquals(List.of("newpkg/RenamedProblem"), method.getThrownTypes());

		Code code = method.getCode();
		ConstTypeInstruction constTypeInstruction = findInstruction(code, ConstTypeInstruction.class);
		assertEquals("newpkg/RenamedOuter$RenamedInner", ((InstanceType) constTypeInstruction.type()).internalName());

		CheckCastInstruction checkCastInstruction = findInstruction(code, CheckCastInstruction.class);
		assertEquals("newpkg/RenamedOuter$RenamedInner", ((InstanceType) checkCastInstruction.type()).internalName());

		NewArrayInstruction newArrayInstruction = findInstruction(code, NewArrayInstruction.class);
		assertEquals("newpkg/RenamedOuter$RenamedInner", newArrayInstruction.componentType().descriptor()
				.substring(1, newArrayInstruction.componentType().descriptor().length() - 1));

		FilledNewArrayInstruction filledNewArrayInstruction = findInstruction(code, FilledNewArrayInstruction.class);
		assertEquals("newpkg/RenamedOuter$RenamedInner", filledNewArrayInstruction.componentType().descriptor()
				.substring(1, filledNewArrayInstruction.componentType().descriptor().length() - 1));

		ConstMethodTypeInstruction methodTypeInstruction = findInstruction(code, ConstMethodTypeInstruction.class);
		assertEquals("(Lnewpkg/RenamedOuter$RenamedInner;[Lnewpkg/RenamedOuter$RenamedInner;)Lnewpkg/RenamedOuter$RenamedInner;",
				methodTypeInstruction.type().descriptor());

		ConstMethodHandleInstruction methodHandleInstruction = findInstruction(code, ConstMethodHandleInstruction.class);
		assertEquals("renamedMethod", methodHandleInstruction.handle().name());
		assertEquals("newpkg/RenamedOuter$RenamedInner", methodHandleInstruction.handle().owner().internalName());

		StaticFieldInstruction staticFieldInstruction = findInstruction(code, StaticFieldInstruction.class);
		assertEquals("renamedField", staticFieldInstruction.name());
		assertEquals("newpkg/RenamedOuter$RenamedInner", staticFieldInstruction.owner().internalName());

		InstanceFieldInstruction instanceFieldInstruction = findInstruction(code, InstanceFieldInstruction.class);
		assertEquals("renamedField", instanceFieldInstruction.name());
		assertEquals("newpkg/RenamedOuter$RenamedInner", instanceFieldInstruction.owner().internalName());

		InvokeInstruction invokeInstruction = findInstruction(code, InvokeInstruction.class);
		assertEquals("renamedMethod", invokeInstruction.name());
		assertEquals("(Lnewpkg/RenamedOuter$RenamedInner;[Lnewpkg/RenamedOuter$RenamedInner;)Lnewpkg/RenamedOuter$RenamedInner;",
				invokeInstruction.type().descriptor());
		assertEquals("newpkg/RenamedOuter$RenamedInner", invokeInstruction.owner().internalName());

		InvokeCustomInstruction invokeCustomInstruction = findInstruction(code, InvokeCustomInstruction.class);
		assertEquals("renamedDynamic", invokeCustomInstruction.name());
		assertEquals("renamedBootstrap", invokeCustomInstruction.handle().name());
		assertEquals("newpkg/RenamedOuter$RenamedInner", invokeCustomInstruction.handle().owner().internalName());

		PackedSwitchInstruction packedSwitchInstruction = findInstruction(code, PackedSwitchInstruction.class);
		SparseSwitchInstruction sparseSwitchInstruction = findInstruction(code, SparseSwitchInstruction.class);
		assertNotSame(packedSwitchInstruction.targets().getFirst(), ((PackedSwitchInstruction)
				findInstruction(source.getMethods().values().iterator().next().getCode(), PackedSwitchInstruction.class)).targets().getFirst());
		assertNotSame(sparseSwitchInstruction.targets().values().iterator().next(), ((SparseSwitchInstruction)
				findInstruction(source.getMethods().values().iterator().next().getCode(), SparseSwitchInstruction.class)).targets().values().iterator().next());

		InstanceOfInstruction instanceOfInstruction = findInstruction(code, InstanceOfInstruction.class);
		assertEquals("newpkg/RenamedOuter$RenamedInner", ((InstanceType) instanceOfInstruction.type()).internalName());

		NewInstanceInstruction newInstanceInstruction = findInstruction(code, NewInstanceInstruction.class);
		assertEquals("newpkg/RenamedOuter$RenamedInner", newInstanceInstruction.type().internalName());

		TryCatch tryCatch = code.tryCatch().getFirst();
		assertEquals("newpkg/RenamedProblem", tryCatch.handlers().getFirst().exceptionType().internalName());
		assertNull(tryCatch.handlers().get(1).exceptionType());

		DebugInformation.LocalVariable localVariable = code.getDebugInfo().locals().getFirst();
		assertEquals("Lnewpkg/RenamedBox<Lnewpkg/RenamedOuter$RenamedInner;>.RenamedHolder<Lnewpkg/RenamedOuter$RenamedInner;>;",
				localVariable.signature());
		assertEquals("newpkg/RenamedOuter$RenamedInner", localVariable.type().descriptor()
				.substring(1, localVariable.type().descriptor().length() - 1));

		assertEquals("oldpkg/Outer$Inner", source.getType().internalName());
		assertEquals("oldMethod", source.getMethods().values().iterator().next().getName());
		assertEquals("oldField", source.getFields().values().iterator().next().getName());
	}

	private static ClassDefinition sampleClass() {
		// Pseudocode:
		// @Anno(
		//    type=Outer$Inner,
		//    member=Owner.oldMethod(Outer$Inner),
		//    enum=Enum.VALUE,
		//    handle=Handle(InvokeVirtual, Outer$Inner, oldMethod, (Outer$Inner)Outer$Inner),
		//    array={Outer$Inner, Owner.oldField}
		// )
		// public class Outer$Inner<T> implements Marker {
		//    @FieldAnno(field=Outer$Inner.oldField)
		//    public static  Outer$Inner oldField = new Outer$Inner();
		//
		//   @MethodAnno(type=Outer$Inner)
		//    public <E> Box<Outer$Inner>.Holder<E> oldMethod(Outer$Inner first, Outer$Inner[] second) throws Problem {
		//       ... see sampleCode() for method body
		//    }
		// }
		ClassDefinition definition = new ClassDefinition(OLD_INNER, Types.instanceType(Object.class), ACC_PUBLIC);
		definition.addInterface(OLD_MARKER);
		definition.setSourceFile("Outer.java");
		definition.setSignature("<T:Ljava/lang/Object;>Ljava/lang/Object;" + OLD_NESTED_SIGNATURE);
		definition.setEnclosingClass(OLD_OUTER);
		definition.setEnclosingMethod(new MemberIdentifier("factory", OLD_ENCLOSING_METHOD_TYPE));
		definition.addInnerClass(new InnerClass(OLD_INNER.internalName(), OLD_OUTER.internalName(), "Inner", ACC_PUBLIC));
		definition.addMemberClass(OLD_MEMBER);
		definition.addAnnotation(new Annotation((byte) Annotation.VISIBILITY_RUNTIME, classAnnotationPart()));

		FieldMember field = new FieldMember("oldField", OLD_INNER, ACC_PUBLIC | ACC_STATIC);
		field.setSignature(OLD_NESTED_SIGNATURE);
		field.setStaticValue(new TypeConstant(OLD_INNER));
		field.addAnnotation(new Annotation((byte) Annotation.VISIBILITY_RUNTIME, new AnnotationPart(
				Types.instanceTypeFromInternalName("oldpkg/FieldAnno"),
				Map.of("field", new MemberConstant(OLD_INNER, new MemberIdentifier("oldField", OLD_INNER)))
		)));
		definition.putField(field);

		MethodMember method = new MethodMember("oldMethod", OLD_METHOD_TYPE, ACC_PUBLIC);
		method.setSignature("<E:Ljava/lang/Object;>(Loldpkg/Outer$Inner;" + OLD_NESTED_SIGNATURE +
				")Loldpkg/Box<Loldpkg/Outer$Inner;>.Holder<TE;>;^Loldpkg/Problem;");
		method.addThrownType(OLD_PROBLEM.internalName());
		method.setParameterNames(List.of("first", "second"));
		method.addAnnotation(new Annotation((byte) Annotation.VISIBILITY_RUNTIME, new AnnotationPart(
				Types.instanceTypeFromInternalName("oldpkg/MethodAnno"),
				Map.of("type", new TypeConstant(OLD_INNER))
		)));
		method.setCode(sampleCode());
		definition.putMethod(method);

		return definition;
	}

	private static AnnotationPart classAnnotationPart() {
		Map<String, Constant> elements = new LinkedHashMap<>();
		elements.put("type", new TypeConstant(OLD_INNER));
		elements.put("member", new MemberConstant(OLD_OWNER,
				new MemberIdentifier("oldMethod", Types.methodTypeFromDescriptor("(Loldpkg/Outer$Inner;)Loldpkg/Outer$Inner;"))));
		elements.put("enum", new EnumConstant(OLD_ENUM, new MemberIdentifier("VALUE", OLD_ENUM)));
		elements.put("handle", new HandleConstant(new Handle(Handle.KIND_INVOKE_INSTANCE, OLD_INNER, "oldMethod",
				Types.methodTypeFromDescriptor("(Loldpkg/Outer$Inner;)Loldpkg/Outer$Inner;"))));
		elements.put("array", new ArrayConstant(List.of(
				new TypeConstant(OLD_INNER),
				new MemberConstant(OLD_INNER, new MemberIdentifier("oldField", OLD_INNER))
		)));
		return new AnnotationPart(Types.instanceTypeFromInternalName("oldpkg/Anno"), elements);
	}

	private static Code sampleCode() {
		Code code = new Code(2, 2, 4);

		Label start = label(0, 0, 10);
		Label branch = label(1, 10, 20);
		Label exit = label(2, 20, 30);
		Label handler = label(3, 30, Label.UNASSIGNED);
		Label catchAll = label(4, 40, Label.UNASSIGNED);

		List<Instruction> instructions = new ArrayList<>();
		instructions.add(start);
		instructions.add(new ConstTypeInstruction(0, OLD_INNER));
		instructions.add(new CheckCastInstruction(0, OLD_INNER));
		instructions.add(new NewArrayInstruction(1, 0, OLD_INNER));
		instructions.add(new FilledNewArrayInstruction(OLD_INNER, 0, 1));
		instructions.add(new ConstMethodTypeInstruction(2, OLD_METHOD_TYPE));
		instructions.add(new ConstMethodHandleInstruction(2, new Handle(Handle.KIND_INVOKE_INSTANCE, OLD_INNER, "oldMethod", OLD_METHOD_TYPE)));
		instructions.add(new StaticFieldInstruction(0, 0, OLD_INNER, "oldField", OLD_INNER));
		instructions.add(new InstanceFieldInstruction(0, 0, 1, OLD_INNER, "oldField", OLD_INNER));
		instructions.add(new InvokeInstruction(Invoke.VIRTUAL, OLD_INNER, "oldMethod", OLD_METHOD_TYPE, 0, 1));
		instructions.add(new InvokeCustomInstruction(
				new Handle(Handle.KIND_INVOKE_STATIC, OLD_INNER, "bootstrap", OLD_BOOTSTRAP_TYPE),
				"oldDynamic",
				OLD_METHOD_TYPE,
				List.of(new TypeConstant(OLD_INNER), new MemberConstant(OLD_INNER, new MemberIdentifier("oldField", OLD_INNER))),
				0, 1
		));
		instructions.add(new PackedSwitchInstruction(0, 10, List.of(branch, exit)));
		LinkedHashMap<Integer, Label> sparseTargets = new LinkedHashMap<>();
		sparseTargets.put(1, branch);
		sparseTargets.put(2, exit);
		instructions.add(new SparseSwitchInstruction(0, sparseTargets));
		instructions.add(new GotoInstruction(branch));
		instructions.add(branch);
		instructions.add(new InstanceOfInstruction(0, 1, OLD_INNER));
		instructions.add(new NewInstanceInstruction(0, OLD_INNER));
		instructions.add(new GotoInstruction(exit));
		instructions.add(handler);
		instructions.add(new ThrowInstruction(0));
		instructions.add(catchAll);
		instructions.add(new ReturnInstruction(0, Return.OBJECT));
		instructions.add(exit);
		instructions.add(new ReturnInstruction(0, Return.OBJECT));

		int offset = 0;
		for (Instruction instruction : instructions) {
			code.addInstruction(instruction);
			if (!(instruction instanceof Label)) {
				code.setInstructionOffset(instruction, offset);
			}
			offset += instruction.byteSize();
		}

		code.addTryCatch(new TryCatch(start, exit, List.of(
				new Handler(handler, OLD_PROBLEM),
				new Handler(catchAll, null)
		)));
		code.setDebugInfo(new DebugInformation(
				List.of(
						new DebugInformation.LineNumber(start, 10),
						new DebugInformation.LineNumber(exit, 30)
				),
				List.of("first", "second"),
				List.of(new DebugInformation.LocalVariable(0, "local", OLD_INNER, OLD_NESTED_SIGNATURE, start, exit))
		));

		return code;
	}

	private static Label label(int index, int position, int lineNumber) {
		Label label = new Label(index, position);
		label.lineNumber(lineNumber);
		return label;
	}

	private static DefinitionRemapper renamingRemapper() {
		return new DefinitionRemapper() {
			@Override
			public @NotNull String mapInternalName(@NotNull String internalName) {
				return switch (internalName) {
					case "oldpkg/Outer" -> "newpkg/RenamedOuter";
					case "oldpkg/Outer$Inner" -> "newpkg/RenamedOuter$RenamedInner";
					case "oldpkg/Outer$Inner$Member" -> "newpkg/RenamedOuter$RenamedInner$RenamedMember";
					case "oldpkg/Box" -> "newpkg/RenamedBox";
					case "oldpkg/Box$Holder" -> "newpkg/RenamedBox$RenamedHolder";
					case "oldpkg/Marker" -> "newpkg/RenamedMarker";
					case "oldpkg/Owner" -> "newpkg/RenamedOwner";
					case "oldpkg/Enum" -> "newpkg/RenamedEnum";
					case "oldpkg/Problem" -> "newpkg/RenamedProblem";
					case "oldpkg/Anno" -> "newpkg/RenamedAnno";
					case "oldpkg/FieldAnno" -> "newpkg/RenamedFieldAnno";
					case "oldpkg/MethodAnno" -> "newpkg/RenamedMethodAnno";
					default -> internalName;
				};
			}

			@Override
			public @NotNull String mapFieldName(@NotNull InstanceType owner, @NotNull String name,
			                                    @NotNull me.darknet.dex.tree.type.ClassType type) {
				return name.equals("oldField") ? "renamedField" : name;
			}

			@Override
			public @NotNull String mapMethodName(@NotNull me.darknet.dex.tree.type.ReferenceType owner, @NotNull String name,
			                                     @NotNull MethodType type) {
				return switch (name) {
					case "factory" -> "createRenamed";
					case "oldMethod" -> "renamedMethod";
					case "bootstrap" -> "renamedBootstrap";
					default -> name;
				};
			}

			@Override
			public @NotNull String mapDynamicMethodName(@NotNull String name, @NotNull MethodType type) {
				return name.equals("oldDynamic") ? "renamedDynamic" : name;
			}
		};
	}

	private static <T> T findInstruction(Code code, Class<T> type) {
		for (Instruction instruction : code.getInstructions()) {
			if (type.isInstance(instruction)) {
				return type.cast(instruction);
			}
		}
		throw new AssertionError("Could not find instruction of type " + type.getSimpleName());
	}
}
