package me.darknet.dex.tree.definitions.annotation;

import me.darknet.dex.tree.definitions.Annotated;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.RecordComponent;
import me.darknet.dex.tree.definitions.Signed;
import me.darknet.dex.tree.definitions.constant.AnnotationConstant;
import me.darknet.dex.tree.definitions.constant.ArrayConstant;
import me.darknet.dex.tree.definitions.constant.ByteConstant;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.MemberConstant;
import me.darknet.dex.tree.definitions.constant.NullConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.definitions.constant.TypeConstant;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
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
			case "dalvik/annotation/NestHost" -> {
				var value = anno.element("value");
				if (value instanceof TypeConstant(Type t) && t instanceof InstanceType it) {
					definition.setNestHost(it);
				} else {
					throw new IllegalStateException("Invalid NestHost annotation value");
				}
				yield ProcessingResult.CONSUMED;
			}
			case "dalvik/annotation/NestMembers" -> {
				var value = anno.element("value");
				if (value instanceof ArrayConstant(List<Constant> constants)) {
					for (Constant constant : constants) {
						if (constant instanceof TypeConstant(Type t) && t instanceof InstanceType it) {
							definition.addNestMember(it);
						} else {
							throw new IllegalStateException("Invalid NestMembers annotation value");
						}
					}
				} else {
					throw new IllegalStateException("Invalid NestMembers annotation value");
				}
				yield ProcessingResult.CONSUMED;
			}
			case "dalvik/annotation/PermittedSubclasses" -> {
				var value = anno.element("value");
				if (value instanceof ArrayConstant(List<Constant> constants)) {
					for (Constant constant : constants) {
						if (constant instanceof TypeConstant(Type t) && t instanceof InstanceType it) {
							definition.addPermittedSubclass(it);
						} else {
							throw new IllegalStateException("Invalid PermittedSubclasses annotation value");
						}
					}
				} else {
					throw new IllegalStateException("Invalid PermittedSubclasses annotation value");
				}
				yield ProcessingResult.CONSUMED;
			}
			case "dalvik/annotation/Record" -> {
				List<RecordComponent> components = processRecordComponents(anno);
				// An annotation whose arrays are missing or mismatched cannot be modelled, so it is kept raw
				// rather than consumed and silently dropped.
				if (components == null)
					yield ProcessingResult.PRESERVE;
				for (RecordComponent component : components)
					definition.addRecordComponent(component);
				yield ProcessingResult.CONSUMED;
			}
			case "dalvik/annotation/AnnotationDefault" -> processAnnotationDefaults(definition, anno);
			default -> ProcessingResult.PRESERVE;
		};
	}

	/**
	 * Distributes the bindings of a {@code dalvik.annotation.AnnotationDefault} annotation onto the element
	 * methods of the annotation interface it is attached to. The annotation holds every default the interface
	 * declares in one sub-annotation keyed by element name, so a single annotation feeds many methods.
	 *
	 * @param definition
	 * 		Annotation interface the annotation is attached to.
	 * @param anno
	 * 		Annotation to read.
	 *
	 * @return {@link ProcessingResult#CONSUMED} when every binding landed on an element method, and
	 * 		{@link ProcessingResult#PRESERVE} otherwise so an unplaceable binding is not silently dropped.
	 */
	private static @NotNull ProcessingResult processAnnotationDefaults(@NotNull ClassDefinition definition,
	                                                                   @NotNull AnnotationPart anno) {
		if (!(anno.element("value") instanceof AnnotationConstant(AnnotationPart bindings)))
			return ProcessingResult.PRESERVE;

		Map<String, MethodMember> elements = new HashMap<>(definition.getMethods().size());
		for (MethodMember method : definition.getMethods().values()) {
			// Only a no-argument method can be an annotation element.
			if (method.getType().parameterTypes().isEmpty())
				elements.put(method.getName(), method);
		}

		// Resolve every binding before assigning any, so a partially applicable annotation is preserved whole
		// rather than being half consumed.
		Map<MethodMember, Constant> resolved = new HashMap<>(bindings.elements().size());
		for (Map.Entry<String, Constant> binding : bindings.elements().entrySet()) {
			MethodMember element = elements.get(binding.getKey());
			if (element == null)
				return ProcessingResult.PRESERVE;
			resolved.put(element, binding.getValue());
		}

		resolved.forEach(MethodMember::setDefaultValue);
		return ProcessingResult.CONSUMED;
	}

	/**
	 * Reads the parallel arrays a {@code dalvik.annotation.Record} annotation is built from. The format stores
	 * one array per component property rather than one record per component, and gets no benefit from doing
	 * so, so the arrays are transposed back into components here.
	 *
	 * @param anno
	 * 		Record annotation to read.
	 *
	 * @return Components in declaration order, or {@code null} when the annotation is absent or malformed.
	 */
	private static @Nullable List<RecordComponent> processRecordComponents(@NotNull AnnotationPart anno) {
		List<Constant> names = recordArrayElement(anno, "componentNames");
		List<Constant> types = recordArrayElement(anno, "componentTypes");
		List<Constant> signatures = recordArrayElement(anno, "componentSignatures");
		List<Constant> visibilities = recordArrayElement(anno, "componentAnnotationVisibilities");
		List<Constant> annotations = recordArrayElement(anno, "componentAnnotations");

		// A record annotation only carries meaning when every array is present and describes the same number
		// of components, so a mismatch means the annotation is not one we can model.
		if (names == null || types == null || signatures == null || visibilities == null || annotations == null)
			return null;
		int componentCount = names.size();
		if (types.size() != componentCount || signatures.size() != componentCount
				|| visibilities.size() != componentCount || annotations.size() != componentCount)
			return null;

		List<RecordComponent> components = new ArrayList<>(componentCount);
		for (int i = 0; i < componentCount; i++) {
			if (!(names.get(i) instanceof StringConstant(String name)))
				return null;
			if (!(types.get(i) instanceof TypeConstant(Type componentType)) || !(componentType instanceof ClassType ct))
				return null;

			String signature = null;
			Constant signatureConstant = signatures.get(i);
			if (signatureConstant instanceof AnnotationConstant(AnnotationPart part)) {
				signature = signatureOf(part);
			} else if (!(signatureConstant instanceof NullConstant)) {
				return null;
			}

			List<Annotation> componentAnnotations = processRecordComponentAnnotations(visibilities.get(i),
					annotations.get(i));
			if (componentAnnotations == null)
				return null;

			components.add(new RecordComponent(name, ct, signature, componentAnnotations));
		}
		return components;
	}

	/**
	 * @param anno
	 * 		Record annotation to read.
	 * @param name
	 * 		Element to read.
	 *
	 * @return Elements of the named array element, or {@code null} when it is absent or not an array.
	 */
	private static @Nullable List<Constant> recordArrayElement(@NotNull AnnotationPart anno, @NotNull String name) {
		if (anno.element(name) instanceof ArrayConstant(List<Constant> constants))
			return constants;
		return null;
	}

	/**
	 * @param visibilities
	 * 		Visibility of each annotation on one component, as bytes.
	 * @param annotations
	 * 		Annotations on one component.
	 *
	 * @return Annotations on the component, or {@code null} when the pair is malformed.
	 */
	private static @Nullable List<Annotation> processRecordComponentAnnotations(@NotNull Constant visibilities,
	                                                                            @NotNull Constant annotations) {
		if (!(visibilities instanceof ArrayConstant(List<Constant> visibilityConstants)))
			return null;
		if (!(annotations instanceof ArrayConstant(List<Constant> annotationConstants)))
			return null;
		if (visibilityConstants.size() != annotationConstants.size())
			return null;

		List<Annotation> result = new ArrayList<>(annotationConstants.size());
		for (int i = 0; i < annotationConstants.size(); i++) {
			if (!(visibilityConstants.get(i) instanceof ByteConstant(byte visibility)))
				return null;
			if (!(annotationConstants.get(i) instanceof AnnotationConstant(AnnotationPart part)))
				return null;
			result.add(new Annotation(visibility, part));
		}
		return result;
	}

	/**
	 * @param signatureAnnotation
	 * 		Annotation holding a component signature.
	 *
	 * @return Concatenated signature the annotation carries.
	 */
	private static @NotNull String signatureOf(@NotNull AnnotationPart signatureAnnotation) {
		StringBuilder sb = new StringBuilder();
		if (signatureAnnotation.element("value") instanceof ArrayConstant(List<Constant> constants)) {
			for (Constant constant : constants)
				if (constant instanceof StringConstant(String value))
					sb.append(value);
		}
		return sb.toString();
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
			// Annotation defaults are not kept here: the format carries them as a single annotation on the
			// annotation interface's class, so processClassAttribute distributes them to the element methods.
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
		appendNestAnnotations(annotations, definition);
		appendPermittedSubclassesAnnotation(annotations, definition);
		appendRecordAnnotation(annotations, definition);
		appendAnnotationDefaultAnnotation(annotations, definition);
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
			     "dalvik/annotation/MemberClasses",
			     "dalvik/annotation/NestHost",
			     "dalvik/annotation/NestMembers",
			     "dalvik/annotation/PermittedSubclasses",
			     "dalvik/annotation/Record",
			     "dalvik/annotation/AnnotationDefault" -> true;
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
			     "dalvik/annotation/MethodParameters",
			     "dalvik/annotation/AnnotationDefault" -> true;
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

	private static void appendNestAnnotations(@NotNull List<Annotation> annotations,
	                                          @NotNull ClassDefinition definition) {
		if (definition.getNestHost() != null) {
			annotations.add(systemAnnotation(
					"dalvik/annotation/NestHost",
					Map.of("value", new TypeConstant(definition.getNestHost()))
			));
		}

		if (definition.getNestMembers().isEmpty())
			return;
		List<Constant> constants = new ArrayList<>(definition.getNestMembers().size());
		for (InstanceType nestMember : definition.getNestMembers())
			constants.add(new TypeConstant(nestMember));
		annotations.add(systemAnnotation(
				"dalvik/annotation/NestMembers",
				Map.of("value", new ArrayConstant(constants))
		));
	}

	private static void appendPermittedSubclassesAnnotation(@NotNull List<Annotation> annotations,
	                                                        @NotNull ClassDefinition definition) {
		if (definition.getPermittedSubclasses().isEmpty())
			return;

		List<Constant> constants = new ArrayList<>(definition.getPermittedSubclasses().size());
		for (InstanceType permittedSubclass : definition.getPermittedSubclasses())
			constants.add(new TypeConstant(permittedSubclass));
		annotations.add(systemAnnotation(
				"dalvik/annotation/PermittedSubclasses",
				Map.of("value", new ArrayConstant(constants))
		));
	}

	private static void appendRecordAnnotation(@NotNull List<Annotation> annotations,
	                                           @NotNull ClassDefinition definition) {
		List<RecordComponent> components = definition.getRecordComponents();
		if (components.isEmpty())
			return;

		// The format keeps one array per component property, so the components are transposed back out here.
		List<Constant> names = new ArrayList<>(components.size());
		List<Constant> types = new ArrayList<>(components.size());
		List<Constant> signatures = new ArrayList<>(components.size());
		List<Constant> visibilities = new ArrayList<>(components.size());
		List<Constant> componentAnnotations = new ArrayList<>(components.size());
		for (RecordComponent component : components) {
			names.add(new StringConstant(component.name()));
			types.add(new TypeConstant(component.type()));
			signatures.add(component.signature() == null
					? NullConstant.INSTANCE
					: new AnnotationConstant(new AnnotationPart(
							Types.instanceTypeFromInternalName("dalvik/annotation/Signature"),
							Map.of("value", new ArrayConstant(List.of(new StringConstant(component.signature())))))));

			List<Constant> annotationVisibilities = new ArrayList<>(component.annotations().size());
			List<Constant> annotationValues = new ArrayList<>(component.annotations().size());
			for (Annotation annotation : component.annotations()) {
				annotationVisibilities.add(new ByteConstant(annotation.visibility()));
				annotationValues.add(new AnnotationConstant(annotation.annotation()));
			}
			visibilities.add(new ArrayConstant(annotationVisibilities));
			componentAnnotations.add(new ArrayConstant(annotationValues));
		}

		Map<String, Constant> elements = new LinkedHashMap<>(5);
		elements.put("componentNames", new ArrayConstant(names));
		elements.put("componentTypes", new ArrayConstant(types));
		elements.put("componentSignatures", new ArrayConstant(signatures));
		elements.put("componentAnnotationVisibilities", new ArrayConstant(visibilities));
		elements.put("componentAnnotations", new ArrayConstant(componentAnnotations));
		annotations.add(systemAnnotation("dalvik/annotation/Record", elements));
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

	/**
	 * Emits the defaults an annotation interface declares as one {@code dalvik.annotation.AnnotationDefault}
	 * annotation, holding a sub-annotation of the interface's own type keyed by element name.
	 *
	 * @param annotations
	 * 		Annotations to append to.
	 * @param definition
	 * 		Annotation interface to read defaults from.
	 */
	private static void appendAnnotationDefaultAnnotation(@NotNull List<Annotation> annotations,
	                                                     @NotNull ClassDefinition definition) {
		// Declaration order is kept so the emitted bindings follow the source order of the elements.
		Map<String, Constant> bindings = new LinkedHashMap<>();
		for (MethodMember method : definition.getMethods().values()) {
			if (method.getDefaultValue() != null)
				bindings.put(method.getName(), method.getDefaultValue());
		}
		if (bindings.isEmpty())
			return;

		annotations.add(systemAnnotation(
				"dalvik/annotation/AnnotationDefault",
				Map.of("value", new AnnotationConstant(new AnnotationPart(definition.getType(), bindings)))
		));
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
