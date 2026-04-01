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
	public static boolean processAttribute(@NotNull Map<String, ClassDefinition> definitionMap,
	                                       @NotNull Annotated annotated,
	                                       @NotNull AnnotationPart anno) {
		if (annotated instanceof Signed signed && processSignedAttribute(signed, anno))
			return true;
		return switch (annotated) {
			case MethodMember method -> processMethodAttribute(method, anno);
			case FieldMember field -> processFieldAttribute(field, anno);
			case ClassDefinition classDef -> processClassAttribute(definitionMap, classDef, anno);
			default -> false;
		};
	}

	private static boolean processClassAttribute(@NotNull Map<String, ClassDefinition> definitionMap,
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
				yield true;
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
				yield true;
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
				yield true;
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
				yield true;
			}
			case "dalvik/annotation/NestHost" -> {
				// TODO: NestHost
				yield true;
			}
			case "dalvik/annotation/NestMembers" -> {
				// TODO: NestMembers
				yield true;
			}
			case "dalvik/annotation/PermittedSubclasses" -> {
				// TODO: PermittedSubclasses
				yield true;
			}
			case "dalvik/annotation/Record" -> {
				// TODO: Record
				yield true;
			}
			default -> false;
		};
	}

	private static boolean processFieldAttribute(@NotNull FieldMember definition, @NotNull AnnotationPart anno) {
		return false;
	}

	private static boolean processMethodAttribute(@NotNull MethodMember definition, @NotNull AnnotationPart anno) {
		return switch (anno.type().internalName()) {
			case "dalvik/annotation/AnnotationDefault" -> {
				var value = anno.element("value");
				if (value instanceof AnnotationConstant(AnnotationPart part)) {
					// TODO: AnnotationDefault
					//  - Should be just one value, not sure how to pick which one if there are multiple provided
				}
				yield true;
			}

			case "dalvik/annotation/MethodParameters" -> {
				// TODO: MethodParameters - Create local variables
				yield true;
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
				yield true;
			}
			default -> false;
		};
	}

	private static boolean processSignedAttribute(@NotNull Signed signed, @NotNull AnnotationPart anno) {
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
			return true;
		}
		return false;
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
}
