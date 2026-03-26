package me.darknet.dex.tree.visitor;

import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.InnerClass;
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
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Utility class for assisting with walking dex tree types with visitors.
 */
public class DexTreeWalker {
    private DexTreeWalker() {}

    public static void accept(@NotNull ClassDefinition definition, @NotNull DexClassVisitor visitor) {
        visitor.visit(definition);

        for (InnerClass innerClass : definition.getInnerClasses()) {
            visitor.visitInnerClass(innerClass);
        }

        for (Annotation annotation : definition.getAnnotations()) {
            DexAnnotationVisitor annotationVisitor = visitor.visitAnnotation(annotation);
            if (annotationVisitor != null)
                accept(annotation, annotationVisitor);
        }

        for (FieldMember field : definition.getFields().values()) {
            DexFieldVisitor fieldVisitor = visitor.visitField(field);
            if (fieldVisitor != null)
                accept(field, fieldVisitor);
        }

        for (MethodMember method : definition.getMethods().values()) {
            DexMethodVisitor methodVisitor = visitor.visitMethod(method);
            if (methodVisitor != null)
                accept(method, methodVisitor);
        }

        visitor.visitEnd();
    }

    public static void accept(@NotNull FieldMember field, @NotNull DexFieldVisitor visitor) {
        visitor.visit(field);

        Constant staticValue = field.getStaticValue();
        if (staticValue != null) {
            DexConstantVisitor constantVisitor = visitor.visitStaticValue(staticValue);
            if (constantVisitor != null)
                accept(staticValue, constantVisitor);
        }

        for (Annotation annotation : field.getAnnotations()) {
            DexAnnotationVisitor annotationVisitor = visitor.visitAnnotation(annotation);
            if (annotationVisitor != null)
                accept(annotation, annotationVisitor);
        }

        visitor.visitEnd();
    }

    public static void accept(@NotNull MethodMember method, @NotNull DexMethodVisitor visitor) {
        visitor.visit(method);

        for (Annotation annotation : method.getAnnotations()) {
            DexAnnotationVisitor annotationVisitor = visitor.visitAnnotation(annotation);
            if (annotationVisitor != null)
                accept(annotation, annotationVisitor);
        }

        Code code = method.getCode();
        if (code != null) {
            DexCodeVisitor codeVisitor = visitor.visitCode(code);
            if (codeVisitor != null)
                accept(code, codeVisitor);
        }

        visitor.visitEnd();
    }

    public static void accept(@NotNull Code code, @NotNull DexCodeVisitor visitor) {
        visitor.visit(code);

        for (TryCatch tryCatch : code.tryCatch()) {
            visitor.visitTryCatch(tryCatch);
            for (Handler handler : tryCatch.handlers()) {
                visitor.visitTryCatchHandler(tryCatch, handler);
            }
        }

        for (Instruction instruction : code.getInstructions()) {
            accept(instruction, visitor);
            if (instruction instanceof InvokeCustomInstruction invokeCustomInstruction) {
                for (int i = 0; i < invokeCustomInstruction.arguments().size(); i++) {
                    Constant argument = invokeCustomInstruction.arguments().get(i);
                    DexConstantVisitor constantVisitor =
                            visitor.visitBootstrapArgument(invokeCustomInstruction, i, argument);
                    if (constantVisitor != null)
                        accept(argument, constantVisitor);
                }
            }
        }

        DebugInformation debugInformation = code.getDebugInfo();
        if (debugInformation != null) {
            visitor.visitDebugInfo(debugInformation);

            if (debugInformation.lineNumbers() != null) {
                for (DebugInformation.LineNumber lineNumber : debugInformation.lineNumbers()) {
                    visitor.visitLineNumber(lineNumber);
                }
            }

            if (debugInformation.locals() != null) {
                for (DebugInformation.LocalVariable localVariable : debugInformation.locals()) {
                    visitor.visitLocalVariable(localVariable);
                }
            }
        }

        visitor.visitEnd();
    }

    public static void accept(@NotNull Annotation annotation, @NotNull DexAnnotationVisitor visitor) {
        accept(annotation.annotation(), visitor);
    }

    public static void accept(@NotNull AnnotationPart annotation, @NotNull DexAnnotationVisitor visitor) {
        visitor.visit(annotation);

        for (Map.Entry<String, Constant> entry : annotation.elements().entrySet()) {
            DexConstantVisitor constantVisitor = visitor.visitElement(entry.getKey(), entry.getValue());
            if (constantVisitor != null)
                accept(entry.getValue(), constantVisitor);
        }

        visitor.visitEnd();
    }

    public static void accept(@NotNull Constant constant, @NotNull DexConstantVisitor visitor) {
        visitor.visitConstant(constant);

        switch (constant) {
            case AnnotationConstant annotationConstant -> {
                DexAnnotationVisitor annotationVisitor = visitor.visitAnnotationConstant(annotationConstant);
                if (annotationVisitor != null)
                    accept(annotationConstant.annotation(), annotationVisitor);
            }
            case ArrayConstant arrayConstant -> {
                DexConstantVisitor constantVisitor = visitor.visitArrayConstant(arrayConstant);
                if (constantVisitor != null) {
                    for (Constant entry : arrayConstant.constants()) {
                        accept(entry, constantVisitor);
                    }
                }
            }
            case BoolConstant boolConstant -> visitor.visitBoolConstant(boolConstant);
            case ByteConstant byteConstant -> visitor.visitByteConstant(byteConstant);
            case CharConstant charConstant -> visitor.visitCharConstant(charConstant);
            case DoubleConstant doubleConstant -> visitor.visitDoubleConstant(doubleConstant);
            case EnumConstant enumConstant -> visitor.visitEnumConstant(enumConstant);
            case FloatConstant floatConstant -> visitor.visitFloatConstant(floatConstant);
            case HandleConstant handleConstant -> visitor.visitHandleConstant(handleConstant);
            case IntConstant intConstant -> visitor.visitIntConstant(intConstant);
            case LongConstant longConstant -> visitor.visitLongConstant(longConstant);
            case MemberConstant memberConstant -> visitor.visitMemberConstant(memberConstant);
            case NullConstant nullConstant -> visitor.visitNullConstant(nullConstant);
            case ShortConstant shortConstant -> visitor.visitShortConstant(shortConstant);
            case StringConstant stringConstant -> visitor.visitStringConstant(stringConstant);
            case TypeConstant typeConstant -> visitor.visitTypeConstant(typeConstant);
        }

        visitor.visitEnd();
    }

    public static void accept(@NotNull Instruction instruction, @NotNull DexInstructionVisitor visitor) {
        visitor.visitInstruction(instruction);

        switch (instruction) {
            case ArrayInstruction arrayInstruction -> visitor.visitArrayInstruction(arrayInstruction);
            case ArrayLengthInstruction arrayLengthInstruction -> visitor.visitArrayLengthInstruction(arrayLengthInstruction);
            case Binary2AddrInstruction binary2AddrInstruction -> visitor.visitBinary2AddrInstruction(binary2AddrInstruction);
            case BinaryInstruction binaryInstruction -> visitor.visitBinaryInstruction(binaryInstruction);
            case BinaryLiteralInstruction binaryLiteralInstruction -> visitor.visitBinaryLiteralInstruction(binaryLiteralInstruction);
            case BranchInstruction branchInstruction -> visitor.visitBranchInstruction(branchInstruction);
            case BranchZeroInstruction branchZeroInstruction -> visitor.visitBranchZeroInstruction(branchZeroInstruction);
            case CheckCastInstruction checkCastInstruction -> visitor.visitCheckCastInstruction(checkCastInstruction);
            case CompareInstruction compareInstruction -> visitor.visitCompareInstruction(compareInstruction);
            case ConstInstruction constInstruction -> visitor.visitConstInstruction(constInstruction);
            case ConstMethodHandleInstruction constMethodHandleInstruction -> visitor.visitConstMethodHandleInstruction(constMethodHandleInstruction);
            case ConstMethodTypeInstruction constMethodTypeInstruction -> visitor.visitConstMethodTypeInstruction(constMethodTypeInstruction);
            case ConstStringInstruction constStringInstruction -> visitor.visitConstStringInstruction(constStringInstruction);
            case ConstTypeInstruction constTypeInstruction -> visitor.visitConstTypeInstruction(constTypeInstruction);
            case ConstWideInstruction constWideInstruction -> visitor.visitConstWideInstruction(constWideInstruction);
            case FillArrayDataInstruction fillArrayDataInstruction -> visitor.visitFillArrayDataInstruction(fillArrayDataInstruction);
            case FilledNewArrayInstruction filledNewArrayInstruction -> visitor.visitFilledNewArrayInstruction(filledNewArrayInstruction);
            case GotoInstruction gotoInstruction -> visitor.visitGotoInstruction(gotoInstruction);
            case InstanceFieldInstruction instanceFieldInstruction -> visitor.visitInstanceFieldInstruction(instanceFieldInstruction);
            case InstanceOfInstruction instanceOfInstruction -> visitor.visitInstanceOfInstruction(instanceOfInstruction);
            case InvokeCustomInstruction invokeCustomInstruction -> visitor.visitInvokeCustomInstruction(invokeCustomInstruction);
            case InvokeInstruction invokeInstruction -> visitor.visitInvokeInstruction(invokeInstruction);
            case Label label -> visitor.visitLabel(label);
            case MonitorInstruction monitorInstruction -> visitor.visitMonitorInstruction(monitorInstruction);
            case MoveExceptionInstruction moveExceptionInstruction -> visitor.visitMoveExceptionInstruction(moveExceptionInstruction);
            case MoveInstruction moveInstruction -> visitor.visitMoveInstruction(moveInstruction);
            case MoveObjectInstruction moveObjectInstruction -> visitor.visitMoveObjectInstruction(moveObjectInstruction);
            case MoveResultInstruction moveResultInstruction -> visitor.visitMoveResultInstruction(moveResultInstruction);
            case MoveWideInstruction moveWideInstruction -> visitor.visitMoveWideInstruction(moveWideInstruction);
            case NewArrayInstruction newArrayInstruction -> visitor.visitNewArrayInstruction(newArrayInstruction);
            case NewInstanceInstruction newInstanceInstruction -> visitor.visitNewInstanceInstruction(newInstanceInstruction);
            case NopInstruction nopInstruction -> visitor.visitNopInstruction(nopInstruction);
            case PackedSwitchInstruction packedSwitchInstruction -> visitor.visitPackedSwitchInstruction(packedSwitchInstruction);
            case ReturnInstruction returnInstruction -> visitor.visitReturnInstruction(returnInstruction);
            case SparseSwitchInstruction sparseSwitchInstruction -> visitor.visitSparseSwitchInstruction(sparseSwitchInstruction);
            case StaticFieldInstruction staticFieldInstruction -> visitor.visitStaticFieldInstruction(staticFieldInstruction);
            case ThrowInstruction throwInstruction -> visitor.visitThrowInstruction(throwInstruction);
            case UnaryInstruction unaryInstruction -> visitor.visitUnaryInstruction(unaryInstruction);
        }
    }
}
