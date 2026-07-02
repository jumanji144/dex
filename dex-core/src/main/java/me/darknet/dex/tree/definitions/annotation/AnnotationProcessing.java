package me.darknet.dex.tree.definitions.annotation;

import me.darknet.dex.tree.definitions.Annotated;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.Signed;
import me.darknet.dex.tree.definitions.constant.AnnotationConstant;
import me.darknet.dex.tree.definitions.constant.ArrayConstant;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.MemberConstant;
import me.darknet.dex.tree.definitions.constant.NullConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.definitions.constant.TypeConstant;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// TODO: Implement annotation processing for missing items
//  - can reference https://github.com/Col-E/r8/blob/master/src/main/java/com/android/tools/r8/graph/DexAnnotation.java for impl details
public class AnnotationProcessing {
	// TODO: I made these booleans so that we could "drop" the annotation if we knew how to process it.
	//       This assumes we would write back the annotation with our processed metadata.
	//       Example:
	//        - We see dalvik/annotation/Signature and call 'Annotated.setSignature'
	//        - We drop the annotation on the 'Annotated' type, but now have a signature assigned
	//        - When we write our 'Annotated' thing back to dex, we re-create the annotated from the current signature string
	//        - This allows us to modify the signature via setters without having to duplicate work by digging through annotations
	public static @NotNull ProcessingResult processAttribute(@NotNull Map<String, ClassDefinition> definitionMap,
	                                                         @NotNull Annotated annotated,
	                                                         @NotNull AnnotationPart anno) {
		if (annotated instanceof Signed signed) {
			ProcessingResult result = processSignedAttribute(signed, anno);
			if (result != ProcessingResult.PRESERVE)
				return result;
		}
		return switch (annotated) {
			case MethodMember method -> processMethodAttribute(method, anno);
			case FieldMember field -> processFieldAttribute(field, anno);
			case ClassDefinition classDef -> processClassAttribute(definitionMap, classDef, anno);
			default -> ProcessingResult.PRESERVE;
		};
	}

	private static @NotNull ProcessingResult processClassAttribute(@NotNull Map<String, ClassDefinition> definitionMap,
	                                                               @NotNull ClassDefinition definition,
	                                                               @NotNull AnnotationPart anno) {
		return switch (anno.type().internalName()) {
			case "dalvik/annotation/EnclosingClass" -> {
				var value = anno.element("value");
				if (value instanceof TypeConstant(Type t) && t instanceof InstanceType it) {
					definition.setEnclosingClass(it);
					addInferredInnerClass(definitionMap, definition, it.internalName(), definition.getAccess());
				} else {
					throw new IllegalStateException("Invalid EnclosingClass annotation value");
				}
				yield ProcessingResult.CONSUMED;
			}
			case "dalvik/annotation/EnclosingMethod" -> {
				var value = anno.element("value");
				if (value instanceof MemberConstant(InstanceType owner, MemberIdentifier member)) {
					definition.setEnclosingClass(owner);
					definition.setEnclosingMethod(member);
					addInferredInnerClass(definitionMap, definition, owner.internalName(), definition.getAccess());
				} else {
					throw new IllegalStateException("Invalid EnclosingMethod annotation value");
				}
				yield ProcessingResult.CONSUMED;
			}
			case "dalvik/annotation/InnerClass" -> {
				var name = anno.element("name");
				var access = anno.element("accessFlags");

				if (access instanceof IntConstant(int flags)) {
					if (name instanceof StringConstant(String nameStr)) {
						String outerClassName = resolveOuterClassName(definitionMap, definition);
						if (outerClassName != null)
							addInnerClass(definitionMap, definition,
									new InnerClass(definition.getType().internalName(), outerClassName, nameStr, flags));
					} else if (name instanceof NullConstant) {
						String outerClassName = resolveOuterClassName(definitionMap, definition);
						if (outerClassName != null)
							addInnerClass(definitionMap, definition,
									new InnerClass(definition.getType().internalName(), outerClassName, null, flags));
					}
				} else {
					throw new IllegalStateException("Invalid InnerClass annotation value");
				}
				yield ProcessingResult.CONSUMED;
			}
			case "dalvik/annotation/MemberClasses" -> {
				var value = anno.element("value");
				if (value instanceof ArrayConstant(List<Constant> constants)) {
					for (Constant constant : constants) {
						if (constant instanceof TypeConstant(Type t) && t instanceof InstanceType it) {
							definition.addMemberClass(it);
						} else {
							throw new IllegalStateException("Invalid MemberClasses annotation value");
						}
					}
				} else {
					throw new IllegalStateException("Invalid MemberClasses annotation value");
				}
				yield ProcessingResult.CONSUMED;
			}
			// TODO: Handle these annotations later
			//  - NestHost, NestMembers, PermittedSubclasses, Record
			case "dalvik/annotation/NestHost",
			     "dalvik/annotation/NestMembers",
			     "dalvik/annotation/PermittedSubclasses",
			     "dalvik/annotation/Record" -> ProcessingResult.PRESERVE;
			default -> ProcessingResult.PRESERVE;
		};
	}

	private static @NotNull ProcessingResult processFieldAttribute(@NotNull FieldMember definition,
	                                                               @NotNull AnnotationPart anno) {
		return ProcessingResult.PRESERVE;
	}

	private static @NotNull ProcessingResult processMethodAttribute(@NotNull MethodMember definition,
	                                                                @NotNull AnnotationPart anno) {
		return switch (anno.type().internalName()) {
			case "dalvik/annotation/MethodParameters" -> {
				var namesElement = anno.element("names");
				var flagsElement = anno.element("accessFlags");
				if (namesElement instanceof ArrayConstant(List<Constant> nameConstants)) {
					List<String> paramNames = new ArrayList<>(nameConstants.size());
					for (Constant constant : nameConstants) {
						if (constant instanceof StringConstant(String value)) {
							paramNames.add(value);
						} else if (constant instanceof NullConstant) {
							paramNames.add(null);
						}
					}
					definition.setParameterNames(paramNames);
				}
				if (flagsElement instanceof ArrayConstant(List<Constant> flagConstants)) {
					List<Integer> flags = new ArrayList<>(flagConstants.size());
					for (Constant constant : flagConstants) {
						if (constant instanceof IntConstant(int value))
							flags.add(value);
					}
					definition.setParameterAccessFlags(flags);
				}
				yield ProcessingResult.CONSUMED;
			}
			case "dalvik/annotation/Throws" -> {
				var value = anno.element("value");
				if (value instanceof ArrayConstant(List<Constant> constants)) {
					for (Constant constant : constants) {
						if (constant instanceof TypeConstant(Type type) && type instanceof InstanceType thrownType) {
							definition.addThrownType(thrownType.internalName());
						}
					}
				}
				yield ProcessingResult.CONSUMED;
			}
			case "dalvik/annotation/AnnotationDefault" -> {
				var value = anno.element("value");
				if (value instanceof AnnotationConstant(AnnotationPart part)) {
					// TODO: AnnotationDefault
					//  - Should be just one value, not sure how to pick which one if there are multiple provided
				}
				yield ProcessingResult.PRESERVE;
			}
			default -> ProcessingResult.PRESERVE;
		};
	}

	private static @NotNull ProcessingResult processSignedAttribute(@NotNull Signed signed, @NotNull AnnotationPart anno) {
		if ("dalvik/annotation/Signature".equals(anno.type().internalName())) {
			var element = anno.element("value");
			if (element instanceof StringConstant(String value)) {
				signed.setSignature(value);
			} else if (element instanceof ArrayConstant(List<Constant> constants)) {
				StringBuilder sb = new StringBuilder();
				for (Constant constant : constants)
					if (constant instanceof StringConstant(String value))
						sb.append(value);
				signed.setSignature(sb.toString());
			} else {
				throw new IllegalStateException("Invalid Signature annotation value");
			}
			return ProcessingResult.CONSUMED;
		}
		return ProcessingResult.PRESERVE;
	}

	/**
	 * Emit annotations to the given class to preserve metadata like signatures, inner class relations, etc.
	 * @param definition The class definition to export annotations for.
	 * @return A list of annotations to be added to the class definition.
	 */
	public static @NotNull List<Annotation> exportClassAnnotations(@NotNull ClassDefinition definition) {
		List<Annotation> annotations = new ArrayList<>();
		for (Annotation annotation : definition.getAnnotations()) {
			if (!isSupportedClassSystemAnnotation(annotation))
				annotations.add(annotation);
		}

		appendSignatureAnnotation(annotations, definition.getSignature());
		appendEnclosingAnnotations(annotations, definition);
		appendInnerClassAnnotation(annotations, definition);
		appendMemberClassesAnnotation(annotations, definition);
		return annotations;
	}

	/**
	 * Emit annotations to the given field to preserve metadata like signatures, etc.
	 * @param field The field member to export annotations for.
	 * @return A list of annotations to be added to the field member.
	 */
	public static @NotNull List<Annotation> exportFieldAnnotations(@NotNull FieldMember field) {
		List<Annotation> annotations = new ArrayList<>();
		for (Annotation annotation : field.getAnnotations()) {
			if (!isSupportedFieldSystemAnnotation(annotation))
				annotations.add(annotation);
		}
		appendSignatureAnnotation(annotations, field.getSignature());
		return annotations;
	}

	/**
	 * Emit annotations to the given method to preserve metadata like signatures, throws, method parameters, etc.
	 * @param method The method member to export annotations for.
	 * @return A list of annotations to be added to the method member.
	 */
	public static @NotNull List<Annotation> exportMethodAnnotations(@NotNull MethodMember method) {
		List<Annotation> annotations = new ArrayList<>();
		for (Annotation annotation : method.getAnnotations()) {
			if (!isSupportedMethodSystemAnnotation(annotation))
				annotations.add(annotation);
		}
		appendSignatureAnnotation(annotations, method.getSignature());
		appendThrowsAnnotation(annotations, method);
		appendMethodParametersAnnotation(annotations, method);
		return annotations;
	}

	private static boolean isSupportedClassSystemAnnotation(@NotNull Annotation annotation) {
		if (annotation.visibility() != Annotation.VISIBILITY_SYSTEM)
			return false;
		return switch (annotation.annotation().type().internalName()) {
			case "dalvik/annotation/Signature",
			     "dalvik/annotation/EnclosingClass",
			     "dalvik/annotation/EnclosingMethod",
			     "dalvik/annotation/InnerClass",
			     "dalvik/annotation/MemberClasses" -> true;
			default -> false;
		};
	}

	private static boolean isSupportedFieldSystemAnnotation(@NotNull Annotation annotation) {
		return annotation.visibility() == Annotation.VISIBILITY_SYSTEM
				&& "dalvik/annotation/Signature".equals(annotation.annotation().type().internalName());
	}

	private static boolean isSupportedMethodSystemAnnotation(@NotNull Annotation annotation) {
		if (annotation.visibility() != Annotation.VISIBILITY_SYSTEM)
			return false;
		return switch (annotation.annotation().type().internalName()) {
			case "dalvik/annotation/Signature",
			     "dalvik/annotation/Throws",
			     "dalvik/annotation/MethodParameters" -> true;
			default -> false;
		};
	}

	private static void appendSignatureAnnotation(@NotNull List<Annotation> annotations, @Nullable String signature) {
		if (signature == null)
			return;
		annotations.add(systemAnnotation(
				"dalvik/annotation/Signature",
				Map.of("value", new ArrayConstant(List.of(new StringConstant(signature))))
		));
	}

	private static void appendEnclosingAnnotations(@NotNull List<Annotation> annotations,
	                                               @NotNull ClassDefinition definition) {
		if (definition.getEnclosingMethod() != null && definition.getEnclosingClass() != null) {
			annotations.add(systemAnnotation(
					"dalvik/annotation/EnclosingMethod",
					Map.of("value", new MemberConstant(definition.getEnclosingClass(), definition.getEnclosingMethod()))
			));
		} else if (definition.getEnclosingClass() != null) {
			annotations.add(systemAnnotation(
					"dalvik/annotation/EnclosingClass",
					Map.of("value", new TypeConstant(definition.getEnclosingClass()))
			));
		}
	}

	private static void appendInnerClassAnnotation(@NotNull List<Annotation> annotations,
	                                               @NotNull ClassDefinition definition) {
		for (InnerClass innerClass : definition.getInnerClasses()) {
			if (!definition.getType().internalName().equals(innerClass.innerClassName()))
				continue;
			Constant innerName = innerClass.innerName() == null
					? NullConstant.INSTANCE
					: new StringConstant(innerClass.innerName());
			annotations.add(systemAnnotation(
					"dalvik/annotation/InnerClass",
					Map.of("name", innerName, "accessFlags", new IntConstant(innerClass.access()))
			));
			return;
		}
	}

	private static void appendMemberClassesAnnotation(@NotNull List<Annotation> annotations,
	                                                  @NotNull ClassDefinition definition) {
		if (definition.getMemberClasses().isEmpty())
			return;

		List<Constant> constants = new ArrayList<>(definition.getMemberClasses().size());
		for (InstanceType memberClass : definition.getMemberClasses())
			constants.add(new TypeConstant(memberClass));
		annotations.add(systemAnnotation(
				"dalvik/annotation/MemberClasses",
				Map.of("value", new ArrayConstant(constants))
		));
	}

	private static void appendThrowsAnnotation(@NotNull List<Annotation> annotations,
	                                           @NotNull MethodMember method) {
		if (method.getThrownTypes().isEmpty())
			return;

		List<Constant> constants = new ArrayList<>(method.getThrownTypes().size());
		for (String thrownType : method.getThrownTypes())
			constants.add(new TypeConstant(Types.instanceTypeFromInternalName(thrownType)));
		annotations.add(systemAnnotation(
				"dalvik/annotation/Throws",
				Map.of("value", new ArrayConstant(constants))
		));
	}

	private static void appendMethodParametersAnnotation(@NotNull List<Annotation> annotations,
	                                                     @NotNull MethodMember method) {
		if (method.getParameterNames() == null || method.getParameterNames().isEmpty())
			return;

		int parameterCount = method.getType().parameterTypes().size();
		List<Constant> names = new ArrayList<>(parameterCount);
		List<Constant> accessFlags = new ArrayList<>(parameterCount);
		List<String> parameterNames = method.getParameterNames();
		List<Integer> parameterAccessFlags = method.getParameterAccessFlags();
		for (int i = 0; i < parameterCount; i++) {
			String name = i < parameterNames.size() ? parameterNames.get(i) : null;
			names.add(name == null ? NullConstant.INSTANCE : new StringConstant(name));
			int flags = i < parameterAccessFlags.size() ? parameterAccessFlags.get(i) : 0;
			accessFlags.add(new IntConstant(flags));
		}
		Map<String, Constant> elements = new LinkedHashMap<>(2);
		elements.put("names", new ArrayConstant(names));
		elements.put("accessFlags", new ArrayConstant(accessFlags));
		annotations.add(systemAnnotation("dalvik/annotation/MethodParameters", elements));
	}

	private static @NotNull Annotation systemAnnotation(@NotNull String internalName,
	                                                    @NotNull Map<String, Constant> elements) {
		return new Annotation((byte) Annotation.VISIBILITY_SYSTEM,
				new AnnotationPart(Types.instanceTypeFromInternalName(internalName), elements));
	}

	private static void addInferredInnerClass(@NotNull Map<String, ClassDefinition> definitionMap,
	                                          @NotNull ClassDefinition definition,
	                                          @NotNull String outerClassName,
	                                          int access) {
		String innerClassName = definition.getType().internalName();
		String innerName = Types.inferInnerName(innerClassName, outerClassName);
		addInnerClass(definitionMap, definition, new InnerClass(innerClassName, outerClassName, innerName, access));
	}

	private static void addInnerClass(@NotNull Map<String, ClassDefinition> definitionMap,
	                                  @NotNull ClassDefinition definition,
	                                  @NotNull InnerClass innerClass) {
		definition.addInnerClass(innerClass);
		ClassDefinition outer = definitionMap.get(innerClass.outerClassName());
		if (outer != null)
			outer.addInnerClass(innerClass);
	}

	private static @Nullable String resolveOuterClassName(@NotNull Map<String, ClassDefinition> definitionMap,
	                                                      @NotNull ClassDefinition definition) {
		if (definition.getEnclosingClass() != null)
			return definition.getEnclosingClass().internalName();

		String name = definition.getType().internalName();
		for (int boundary = name.lastIndexOf('$'); boundary >= 0; boundary = name.lastIndexOf('$', boundary - 1)) {
			String candidate = name.substring(0, boundary);
			if (definitionMap.containsKey(candidate))
				return candidate;
		}
		int boundary = name.lastIndexOf('$');
		return boundary > 0 ? name.substring(0, boundary) : null;
	}

	public enum ProcessingResult {
		CONSUMED,
		PRESERVE,
		ERROR
	}
}
