package me.darknet.dex.tree.remap;

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
import me.darknet.dex.tree.definitions.constant.AnnotationConstant;
import me.darknet.dex.tree.definitions.constant.ArrayConstant;
import me.darknet.dex.tree.definitions.constant.BoolConstant;
import me.darknet.dex.tree.definitions.constant.ByteConstant;
import me.darknet.dex.tree.definitions.constant.CharConstant;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.DoubleConstant;
import me.darknet.dex.tree.definitions.constant.EnumConstant;
import me.darknet.dex.tree.definitions.constant.FloatConstant;
import me.darknet.dex.tree.definitions.constant.Handle;
import me.darknet.dex.tree.definitions.constant.HandleConstant;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.LongConstant;
import me.darknet.dex.tree.definitions.constant.MemberConstant;
import me.darknet.dex.tree.definitions.constant.NullConstant;
import me.darknet.dex.tree.definitions.constant.ShortConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.definitions.constant.TypeConstant;
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.ArrayLengthInstruction;
import me.darknet.dex.tree.definitions.instructions.Binary2AddrInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BinaryLiteralInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.CheckCastInstruction;
import me.darknet.dex.tree.definitions.instructions.CompareInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstMethodHandleInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstMethodTypeInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstStringInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstTypeInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstWideInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.definitions.instructions.FilledNewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceOfInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.MonitorInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveObjectInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveResultInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveWideInstruction;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.NopInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.SparseSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import me.darknet.dex.tree.definitions.instructions.UnaryInstruction;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.type.Type;
import me.darknet.dex.tree.type.TypeParser;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static me.darknet.dex.tree.definitions.constant.Handle.*;
import static me.darknet.dex.tree.definitions.instructions.InvokeInstruction.INVOKE_VIRTUAL;
import static me.darknet.dex.tree.definitions.instructions.InvokeInstruction.INVOKE_VIRTUAL_RANGE;

/**
 * Utility class for remapping class definitions using a {@link DefinitionRemapper}.
 */
public class ClassDefinitionRemapper {
	private final DefinitionRemapper remapper;

	/**
	 * @param remapper Mapping implementation to use for remapping definitions, types, member identifiers, etc.
	 */
	public ClassDefinitionRemapper(@NotNull DefinitionRemapper remapper) {
		this.remapper = Objects.requireNonNull(remapper, "remapper");
	}

	/**
	 * @param source Class definition to remap.
	 * @return Remapped copy of the given class definition.
	 */
	public @NotNull ClassDefinition remap(@NotNull ClassDefinition source) {
		return new CopyContext(remapper).remapClass(Objects.requireNonNull(source, "source"));
	}

	/**
	 * For mapping we want to yield a mapped copy of any given definition without modifying the original.
	 * This class walks the definition tree and produces remapped copies of all definitions, instructions,
	 * constants, etc. using the provided remapper.
	 */
	private static final class CopyContext {
		private static final int INVOKE_RANGE_OFFSET = INVOKE_VIRTUAL_RANGE - INVOKE_VIRTUAL;

		private final DefinitionRemapper remapper;

		private CopyContext(@NotNull DefinitionRemapper remapper) {
			this.remapper = remapper;
		}

		private @NotNull ClassDefinition remapClass(@NotNull ClassDefinition source) {
			InstanceType mappedType = TypeMapping.mapInstanceType(remapper, source.getType());
			InstanceType mappedSuperClass = source.getSuperClass() == null
					? null
					: TypeMapping.mapInstanceType(remapper, source.getSuperClass());

			ClassDefinition target = new ClassDefinition(mappedType, mappedSuperClass, source.getAccess());
			target.setSourceFile(source.getSourceFile());
			target.setSignature(SignatureMapping.mapClassSignature(remapper, source.getSignature()));

			for (InstanceType interfaceType : source.getInterfaces())
				target.addInterface(TypeMapping.mapInstanceType(remapper, interfaceType));

			InstanceType enclosingClass = source.getEnclosingClass();
			if (enclosingClass != null)
				target.setEnclosingClass(TypeMapping.mapInstanceType(remapper, enclosingClass));

			MemberIdentifier enclosingMethod = source.getEnclosingMethod();
			if (enclosingMethod != null)
				target.setEnclosingMethod(remapEnclosingMethod(enclosingClass, enclosingMethod));

			for (InnerClass innerClass : source.getInnerClasses())
				target.addInnerClass(remapInnerClass(innerClass));

			for (InstanceType memberClass : source.getMemberClasses())
				target.addMemberClass(TypeMapping.mapInstanceType(remapper, memberClass));

			for (Annotation annotation : source.getAnnotations())
				target.addAnnotation(remapAnnotation(annotation));

			for (FieldMember field : source.getFields().values())
				target.putField(remapField(source.getType(), field));

			for (MethodMember method : source.getMethods().values())
				target.putMethod(remapMethod(source.getType(), method));

			return target;
		}

		private @NotNull FieldMember remapField(@NotNull InstanceType owner, @NotNull FieldMember source) {
			ClassType mappedType = TypeMapping.mapClassType(remapper, source.getType());
			String mappedName = TypeMapping.mapFieldName(remapper, owner, source.getName(), source.getType());

			FieldMember target = new FieldMember(mappedName, mappedType, source.getAccess());
			target.setSignature(SignatureMapping.mapTypeSignature(remapper, source.getSignature()));
			if (source.getStaticValue() != null)
				target.setStaticValue(remapConstant(source.getStaticValue()));

			for (Annotation annotation : source.getAnnotations())
				target.addAnnotation(remapAnnotation(annotation));

			return target;
		}

		private @NotNull MethodMember remapMethod(@NotNull InstanceType owner, @NotNull MethodMember source) {
			MethodType mappedType = TypeMapping.mapMethodType(remapper, source.getType());
			String mappedName = TypeMapping.mapMethodName(remapper, owner, source.getName(), source.getType());

			MethodMember target = new MethodMember(mappedName, mappedType, source.getAccess());
			target.setSignature(SignatureMapping.mapMethodSignature(remapper, source.getSignature()));

			List<String> parameterNames = source.getParameterNames();
			if (parameterNames != null)
				target.setParameterNames(List.copyOf(parameterNames));

			if (!source.getThrownTypes().isEmpty()) {
				List<String> thrownTypes = new ArrayList<>(source.getThrownTypes().size());
				for (String thrownType : source.getThrownTypes())
					thrownTypes.add(remapInternalName(thrownType));
				target.setThrownTypes(thrownTypes);
			}

			for (Annotation annotation : source.getAnnotations())
				target.addAnnotation(remapAnnotation(annotation));

			if (source.getCode() != null)
				target.setCode(remapCode(source.getCode()));

			return target;
		}

		private @NotNull Code remapCode(@NotNull Code source) {
			return new CodeCopyContext().remap(source);
		}

		private @NotNull Annotation remapAnnotation(@NotNull Annotation source) {
			return new Annotation(source.visibility(), remapAnnotationPart(source.annotation()));
		}

		private @NotNull AnnotationPart remapAnnotationPart(@NotNull AnnotationPart source) {
			Map<String, Constant> elements = new LinkedHashMap<>(source.elements().size());
			for (Map.Entry<String, Constant> entry : source.elements().entrySet())
				elements.put(entry.getKey(), remapConstant(entry.getValue()));
			return new AnnotationPart(TypeMapping.mapInstanceType(remapper, source.type()), elements);
		}

		private @NotNull Constant remapConstant(@NotNull Constant source) {
			return switch (source) {
				case AnnotationConstant annotationConstant ->
						new AnnotationConstant(remapAnnotationPart(annotationConstant.annotation()));
				case ArrayConstant arrayConstant -> {
					List<Constant> constants = new ArrayList<>(arrayConstant.constants().size());
					for (Constant constant : arrayConstant.constants()) {
						constants.add(remapConstant(constant));
					}
					yield new ArrayConstant(constants);
				}
				case BoolConstant boolConstant -> new BoolConstant(boolConstant.value());
				case ByteConstant byteConstant -> new ByteConstant(byteConstant.value());
				case CharConstant charConstant -> new CharConstant(charConstant.value());
				case DoubleConstant doubleConstant -> new DoubleConstant(doubleConstant.value());
				case EnumConstant enumConstant -> new EnumConstant(
						TypeMapping.mapInstanceType(remapper, enumConstant.owner()),
						TypeMapping.mapFieldIdentifier(remapper, enumConstant.owner(),
								enumConstant.field().name(), (ClassType) TypeParser.parse(enumConstant.field().descriptor()))
				);
				case FloatConstant floatConstant -> new FloatConstant(floatConstant.value());
				case HandleConstant handleConstant -> new HandleConstant(remapHandle(handleConstant.handle()));
				case IntConstant intConstant -> new IntConstant(intConstant.value());
				case LongConstant longConstant -> new LongConstant(longConstant.value());
				case MemberConstant memberConstant -> remapMemberConstant(memberConstant);
				case NullConstant ignored -> NullConstant.INSTANCE;
				case ShortConstant shortConstant -> new ShortConstant(shortConstant.value());
				case StringConstant stringConstant -> new StringConstant(stringConstant.value());
				case TypeConstant typeConstant -> new TypeConstant(TypeMapping.mapType(remapper, typeConstant.type()));
			};
		}

		private @NotNull MemberConstant remapMemberConstant(@NotNull MemberConstant source) {
			InstanceType owner = source.owner();
			MemberIdentifier member = source.member();
			Type type = TypeParser.parse(member.descriptor());
			MemberIdentifier mapped = switch (type) {
				case MethodType methodType ->
						TypeMapping.mapMethodIdentifier(remapper, owner, member.name(), methodType);
				case ClassType classType -> TypeMapping.mapFieldIdentifier(remapper, owner, member.name(), classType);
			};
			return new MemberConstant(TypeMapping.mapInstanceType(remapper, owner), mapped);
		}

		private @NotNull Handle remapHandle(@NotNull Handle source) {
			InstanceType mappedOwner = TypeMapping.mapInstanceType(remapper, source.owner());
			return switch (source.kind()) {
				case KIND_STATIC_PUT, KIND_STATIC_GET,
				     KIND_INSTANCE_PUT, KIND_INSTANCE_GET -> {
					ClassType fieldType = (ClassType) source.type();
					yield new Handle(source.kind(), mappedOwner,
							TypeMapping.mapFieldName(remapper, source.owner(), source.name(), fieldType),
							TypeMapping.mapClassType(remapper, fieldType));
				}
				case KIND_INVOKE_STATIC, KIND_INVOKE_INSTANCE,
				     KIND_INVOKE_CONSTRUCTOR, KIND_INVOKE_DIRECT,
				     KIND_INVOKE_INTERFACE -> {
					MethodType methodType = (MethodType) source.type();
					yield new Handle(source.kind(), mappedOwner,
							TypeMapping.mapMethodName(remapper, source.owner(), source.name(), methodType),
							TypeMapping.mapMethodType(remapper, methodType));
				}
				default -> throw new IllegalStateException("Unexpected handle kind: " + source.kind());
			};
		}

		private @NotNull MemberIdentifier remapEnclosingMethod(@Nullable InstanceType enclosingClass,
		                                                       @NotNull MemberIdentifier identifier) {
			MethodType methodType = (MethodType) TypeParser.parse(identifier.descriptor());
			MethodType mappedType = TypeMapping.mapMethodType(remapper, methodType);
			String mappedName = enclosingClass == null
					? identifier.name()
					: TypeMapping.mapMethodName(remapper, enclosingClass, identifier.name(), methodType);
			return new MemberIdentifier(mappedName, mappedType);
		}

		private @NotNull InnerClass remapInnerClass(@NotNull InnerClass innerClass) {
			String mappedInnerClassName = remapInternalName(innerClass.innerClassName());
			String mappedOuterClassName = remapInternalName(innerClass.outerClassName());

			String mappedInnerName;
			String inferredInnerName = Types.inferInnerName(innerClass.innerClassName(), innerClass.outerClassName());
			if (Objects.equals(innerClass.innerName(), inferredInnerName))
				mappedInnerName = Types.inferInnerName(mappedInnerClassName, mappedOuterClassName);
			 else
				mappedInnerName = innerClass.innerName();


			return new InnerClass(mappedInnerClassName, mappedOuterClassName, mappedInnerName, innerClass.access());
		}

		private @NotNull String remapInternalName(@NotNull String internalName) {
			return Objects.requireNonNull(remapper.mapInternalName(internalName), "mapInternalName must not return null");
		}

		private final class CodeCopyContext {
			private final Map<Label, Label> labels = new IdentityHashMap<>();

			private @NotNull Code remap(@NotNull Code source) {
				Code target = new Code(source.getIn(), source.getOut(), source.getRegisters());

				for (TryCatch tryCatch : source.tryCatch())
					target.addTryCatch(remapTryCatch(tryCatch));

				for (Instruction instruction : source.getInstructions()) {
					Instruction remapped = remapInstruction(instruction);
					target.addInstruction(remapped);

					Integer offset = source.offsetOf(instruction);
					if (offset != null)
						target.setInstructionOffset(remapped, offset);
				}

				if (source.getDebugInfo() != null)
					target.setDebugInfo(remapDebugInfo(source.getDebugInfo()));

				return target;
			}

			private @NotNull TryCatch remapTryCatch(@NotNull TryCatch source) {
				List<Handler> handlers = new ArrayList<>(source.handlers().size());
				for (Handler handler : source.handlers()) {
					InstanceType exceptionType = handler.exceptionType() == null
							? null
							: TypeMapping.mapInstanceType(remapper, handler.exceptionType());
					handlers.add(new Handler(label(handler.handler()), exceptionType));
				}
				return new TryCatch(label(source.begin()), label(source.end()), handlers);
			}

			private @NotNull DebugInformation remapDebugInfo(@NotNull DebugInformation source) {
				List<DebugInformation.LineNumber> lineNumbers = null;
				if (source.lineNumbers() != null) {
					lineNumbers = new ArrayList<>(source.lineNumbers().size());
					for (DebugInformation.LineNumber lineNumber : source.lineNumbers())
						lineNumbers.add(new DebugInformation.LineNumber(label(lineNumber.label()), lineNumber.line()));
				}

				List<String> parameterNames = source.parameterNames() == null ? null : List.copyOf(source.parameterNames());

				List<DebugInformation.LocalVariable> locals = null;
				if (source.locals() != null) {
					locals = new ArrayList<>(source.locals().size());
					for (DebugInformation.LocalVariable local : source.locals()) {
						locals.add(new DebugInformation.LocalVariable(
								local.register(),
								local.name(),
								TypeMapping.mapType(remapper, local.type()),
								SignatureMapping.mapTypeSignature(remapper, local.signature()),
								label(local.start()),
								label(local.end())
						));
					}
				}

				return new DebugInformation(lineNumbers, parameterNames, locals);
			}

			private @NotNull Instruction remapInstruction(@NotNull Instruction source) {
				return switch (source) {
					case ArrayInstruction instruction ->
							new ArrayInstruction(instruction.kind(), instruction.value(), instruction.array(), instruction.index());
					case ArrayLengthInstruction instruction ->
							new ArrayLengthInstruction(instruction.dest(), instruction.array());
					case Binary2AddrInstruction instruction ->
							new Binary2AddrInstruction(instruction.opcode(), instruction.a(), instruction.b());
					case BinaryInstruction instruction ->
							new BinaryInstruction(instruction.opcode(), instruction.dest(), instruction.a(), instruction.b());
					case BinaryLiteralInstruction instruction ->
							new BinaryLiteralInstruction(instruction.opcode(), instruction.dest(), instruction.src(), instruction.constant());
					case BranchInstruction instruction ->
							new BranchInstruction(instruction.test(), instruction.a(), instruction.b(), label(instruction.label()));
					case BranchZeroInstruction instruction ->
							new BranchZeroInstruction(instruction.kind(), instruction.a(), label(instruction.label()));
					case CheckCastInstruction instruction -> new CheckCastInstruction(instruction.register(),
							TypeMapping.mapClassType(remapper, instruction.type()));
					case CompareInstruction instruction ->
							new CompareInstruction(instruction.opcode(), instruction.dest(), instruction.a(), instruction.b());
					case ConstInstruction instruction ->
							new ConstInstruction(instruction.opcode(), instruction.register(), instruction.value());
					case ConstMethodHandleInstruction instruction ->
							new ConstMethodHandleInstruction(instruction.destination(), remapHandle(instruction.handle()));
					case ConstMethodTypeInstruction instruction ->
							new ConstMethodTypeInstruction(instruction.destination(), TypeMapping.mapMethodType(remapper, instruction.type()));
					case ConstStringInstruction instruction ->
							new ConstStringInstruction(instruction.opcode(), instruction.register(), instruction.string());
					case ConstTypeInstruction instruction ->
							new ConstTypeInstruction(instruction.register(), TypeMapping.mapClassType(remapper, instruction.type()));
					case ConstWideInstruction instruction ->
							new ConstWideInstruction(instruction.opcode(), instruction.register(), instruction.value());
					case FillArrayDataInstruction instruction ->
							new FillArrayDataInstruction(instruction.array(), instruction.data().clone(), instruction.elementSize());
					case FilledNewArrayInstruction instruction -> remapFilledNewArrayInstruction(instruction);
					case GotoInstruction instruction ->
							new GotoInstruction(instruction.opcode(), label(instruction.jump()));
					case InstanceFieldInstruction instruction -> new InstanceFieldInstruction(
							instruction.kind(),
							instruction.value(),
							instruction.instance(),
							TypeMapping.mapInstanceType(remapper, instruction.owner()),
							TypeMapping.mapFieldName(remapper, instruction.owner(), instruction.name(), instruction.type()),
							TypeMapping.mapClassType(remapper, instruction.type())
					);
					case InstanceOfInstruction instruction -> new InstanceOfInstruction(
							instruction.destination(),
							instruction.register(),
							TypeMapping.mapClassType(remapper, instruction.type())
					);
					case InvokeCustomInstruction instruction -> remapInvokeCustomInstruction(instruction);
					case InvokeInstruction instruction -> remapInvokeInstruction(instruction);
					case Label instruction -> label(instruction);
					case MonitorInstruction instruction ->
							new MonitorInstruction(instruction.register(), instruction.exit());
					case MoveExceptionInstruction instruction -> new MoveExceptionInstruction(instruction.register());
					case MoveInstruction instruction ->
							new MoveInstruction(instruction.opcode(), instruction.to(), instruction.from());
					case MoveObjectInstruction instruction ->
							new MoveObjectInstruction(instruction.opcode(), instruction.to(), instruction.from());
					case MoveResultInstruction instruction ->
							new MoveResultInstruction(instruction.opcode(), instruction.type(), instruction.to());
					case MoveWideInstruction instruction ->
							new MoveWideInstruction(instruction.opcode(), instruction.to(), instruction.from());
					case NewArrayInstruction instruction -> new NewArrayInstruction(
							instruction.dest(),
							instruction.sizeRegister(),
							TypeMapping.mapClassType(remapper, instruction.componentType())
					);
					case NewInstanceInstruction instruction ->
							new NewInstanceInstruction(instruction.dest(), TypeMapping.mapInstanceType(remapper, instruction.type()));
					case NopInstruction ignored -> new NopInstruction();
					case PackedSwitchInstruction instruction -> remapPackedSwitchInstruction(instruction);
					case ReturnInstruction instruction ->
							new ReturnInstruction(instruction.opcode(), instruction.register(), instruction.type());
					case SparseSwitchInstruction instruction -> remapSparseSwitchInstruction(instruction);
					case StaticFieldInstruction instruction -> new StaticFieldInstruction(
							instruction.kind(),
							instruction.value(),
							TypeMapping.mapInstanceType(remapper, instruction.owner()),
							TypeMapping.mapFieldName(remapper, instruction.owner(), instruction.name(), instruction.type()),
							TypeMapping.mapClassType(remapper, instruction.type())
					);
					case ThrowInstruction instruction -> new ThrowInstruction(instruction.value());
					case UnaryInstruction instruction ->
							new UnaryInstruction(instruction.opcode(), instruction.source(), instruction.dest());
				};
			}

			private @NotNull FilledNewArrayInstruction remapFilledNewArrayInstruction(@NotNull FilledNewArrayInstruction source) {
				ClassType componentType = TypeMapping.mapClassType(remapper, source.componentType());
				if (source.isRange())
					return new FilledNewArrayInstruction(componentType, source.last() - source.first() + 1, source.first());
				return new FilledNewArrayInstruction(componentType, source.registers().clone());
			}

			private @NotNull InvokeInstruction remapInvokeInstruction(@NotNull InvokeInstruction source) {
				ReferenceType mappedOwner = TypeMapping.mapReferenceType(remapper, source.owner());
				String mappedName = TypeMapping.mapMethodName(remapper, source.owner(), source.name(), source.type());
				MethodType mappedType = TypeMapping.mapMethodType(remapper, source.type());

				if (source.isRange())
					return InvokeInstruction.range(source.opcode() + INVOKE_RANGE_OFFSET, mappedOwner, mappedName,
							mappedType, source.last() - source.first() + 1, source.first());
				return new InvokeInstruction(source.opcode(), mappedOwner, mappedName, mappedType, source.arguments().clone());
			}

			private @NotNull InvokeCustomInstruction remapInvokeCustomInstruction(@NotNull InvokeCustomInstruction source) {
				Handle mappedHandle = remapHandle(source.handle());
				String mappedName = TypeMapping.mapDynamicMethodName(remapper, source.name(), source.type());
				MethodType mappedType = TypeMapping.mapMethodType(remapper, source.type());

				List<Constant> arguments = new ArrayList<>(source.arguments().size());
				for (Constant argument : source.arguments())
					arguments.add(remapConstant(argument));

				if (source.isRange())
					return new InvokeCustomInstruction(mappedHandle, mappedName, mappedType, arguments,
							source.last() - source.first() + 1, source.first());
				return new InvokeCustomInstruction(mappedHandle, mappedName, mappedType, arguments,
						source.argumentRegisters().clone());
			}

			private @NotNull PackedSwitchInstruction remapPackedSwitchInstruction(@NotNull PackedSwitchInstruction source) {
				List<Label> targets = new ArrayList<>(source.targets().size());
				for (Label target : source.targets())
					targets.add(label(target));
				return new PackedSwitchInstruction(source.register(), source.firstKey(), targets);
			}

			private @NotNull SparseSwitchInstruction remapSparseSwitchInstruction(@NotNull SparseSwitchInstruction source) {
				Map<Integer, Label> targets = new LinkedHashMap<>(source.targets().size());
				for (Map.Entry<Integer, Label> entry : source.targets().entrySet())
					targets.put(entry.getKey(), label(entry.getValue()));
				return new SparseSwitchInstruction(source.register(), targets);
			}

			private @NotNull Label label(@NotNull Label source) {
				return labels.computeIfAbsent(source, ignored -> {
					Label copy = new Label(source.index(), source.position());
					copy.lineNumber(source.lineNumber());
					return copy;
				});
			}
		}
	}
}
