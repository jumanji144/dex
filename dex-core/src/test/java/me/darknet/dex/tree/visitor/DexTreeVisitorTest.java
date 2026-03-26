package me.darknet.dex.tree.visitor;

import me.darknet.dex.tree.definitions.AccessFlags;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.definitions.constant.AnnotationConstant;
import me.darknet.dex.tree.definitions.constant.ArrayConstant;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.Handle;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction;
import me.darknet.dex.tree.definitions.instructions.NopInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class DexTreeVisitorTest {

    @Test
    void acceptTraversesClassTreeInDeclaredOrder() {
        ClassDefinition definition = sampleDefinition();
        List<String> events = new ArrayList<>();

        definition.accept(new DexClassVisitor() {
            @Override
            public void visit(@NotNull ClassDefinition definition) {
                events.add("class.visit");
            }

            @Override
            public void visitInnerClass(@NotNull InnerClass innerClass) {
                events.add("class.inner:" + innerClass.innerClassName());
            }

            @Override
            public DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
                events.add("class.annotation");
                return recordingAnnotationVisitor(events, "class.annotation");
            }

            @Override
            public DexFieldVisitor visitField(@NotNull FieldMember field) {
                return new DexFieldVisitor() {
                    @Override
                    public void visit(@NotNull FieldMember field) {
                        events.add("field.visit:" + field.getName());
                    }

                    @Override
                    public DexConstantVisitor visitStaticValue(@NotNull Constant value) {
                        events.add("field.static");
                        return recordingConstantVisitor(events, "field.static");
                    }

                    @Override
                    public DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
                        events.add("field.annotation");
                        return recordingAnnotationVisitor(events, "field.annotation");
                    }

                    @Override
                    public void visitEnd() {
                        events.add("field.end");
                    }
                };
            }

            @Override
            public DexMethodVisitor visitMethod(@NotNull MethodMember method) {
                return new DexMethodVisitor() {
                    @Override
                    public void visit(@NotNull MethodMember method) {
                        events.add("method.visit:" + method.getName());
                    }

                    @Override
                    public DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
                        events.add("method.annotation");
                        return recordingAnnotationVisitor(events, "method.annotation");
                    }

                    @Override
                    public DexCodeVisitor visitCode(@NotNull Code code) {
                        events.add("method.code");
                        return new DexCodeVisitor() {
                            @Override
                            public void visit(@NotNull Code code) {
                                events.add("code.visit");
                            }

                            @Override
                            public void visitTryCatch(@NotNull TryCatch tryCatch) {
                                events.add("code.try");
                            }

                            @Override
                            public void visitTryCatchHandler(@NotNull TryCatch tryCatch, @NotNull Handler handler) {
                                events.add(handler.isCatchAll()
                                        ? "code.handler:catchAll"
                                        : "code.handler:" + handler.exceptionType().internalName());
                            }

                            @Override
                            public void visitInstruction(@NotNull Instruction instruction) {
                                events.add("insn:" + instruction.getClass().getSimpleName());
                            }

                            @Override
                            public void visitLabel(@NotNull Label instruction) {
                                events.add("label:" + instruction.index());
                            }

                            @Override
                            public void visitNopInstruction(@NotNull NopInstruction instruction) {
                                events.add("nop");
                            }

                            @Override
                            public void visitInvokeCustomInstruction(@NotNull InvokeCustomInstruction instruction) {
                                events.add("invokeCustom");
                            }

                            @Override
                            public void visitMoveExceptionInstruction(@NotNull MoveExceptionInstruction instruction) {
                                events.add("moveException");
                            }

                            @Override
                            public void visitReturnInstruction(@NotNull ReturnInstruction instruction) {
                                events.add("return");
                            }

                            @Override
                            public DexConstantVisitor visitBootstrapArgument(@NotNull InvokeCustomInstruction instruction,
                                                                             int index,
                                                                             @NotNull Constant argument) {
                                events.add("bootstrap:" + index);
                                return recordingConstantVisitor(events, "bootstrap");
                            }

                            @Override
                            public void visitDebugInfo(@NotNull DebugInformation debugInformation) {
                                events.add("code.debug");
                            }

                            @Override
                            public void visitLineNumber(DebugInformation.@NotNull LineNumber lineNumber) {
                                events.add("code.line:" + lineNumber.line());
                            }

                            @Override
                            public void visitLocalVariable(DebugInformation.@NotNull LocalVariable localVariable) {
                                events.add("code.local:" + localVariable.name());
                            }

                            @Override
                            public void visitEnd() {
                                events.add("code.end");
                            }
                        };
                    }

                    @Override
                    public void visitEnd() {
                        events.add("method.end");
                    }
                };
            }

            @Override
            public void visitEnd() {
                events.add("class.end");
            }
        });

        assertIterableEquals(List.of(
                "class.visit",
                "class.inner:test/Visitor$Inner",
                "class.annotation",
                "class.annotation.visit:test/ClassAnn",
                "class.annotation.element:value",
                "class.annotation.end",
                "field.visit:COUNT",
                "field.static",
                "field.static.const:IntConstant",
                "field.static.int:7",
                "field.static.end",
                "field.annotation",
                "field.annotation.visit:test/FieldAnn",
                "field.annotation.element:value",
                "field.annotation.end",
                "field.end",
                "method.visit:call",
                "method.annotation",
                "method.annotation.visit:test/MethodAnn",
                "method.annotation.element:flag",
                "method.annotation.end",
                "method.code",
                "code.visit",
                "code.try",
                "code.handler:java/lang/RuntimeException",
                "code.handler:catchAll",
                "insn:Label",
                "label:0",
                "insn:NopInstruction",
                "nop",
                "insn:InvokeCustomInstruction",
                "invokeCustom",
                "bootstrap:0",
                "bootstrap.const:StringConstant",
                "bootstrap.string:bootstrap",
                "bootstrap.end",
                "bootstrap:1",
                "bootstrap.const:IntConstant",
                "bootstrap.int:2",
                "bootstrap.end",
                "insn:Label",
                "label:1",
                "insn:ReturnInstruction",
                "return",
                "insn:Label",
                "label:2",
                "insn:MoveExceptionInstruction",
                "moveException",
                "insn:Label",
                "label:3",
                "insn:ReturnInstruction",
                "return",
                "code.debug",
                "code.line:42",
                "code.local:value",
                "code.end",
                "method.end",
                "class.end"
        ), events);
    }

    @Test
    void nullVisitorsSkipOnlyTheirOwnSubtrees() {
        ClassDefinition definition = sampleDefinition();
        List<String> events = new ArrayList<>();

        definition.accept(new DexClassVisitor() {
            @Override
            public void visit(@NotNull ClassDefinition definition) {
                events.add("class.visit");
            }

            @Override
            public DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
                events.add("class.annotation");
                return null;
            }

            @Override
            public DexFieldVisitor visitField(@NotNull FieldMember field) {
                return new DexFieldVisitor() {
                    @Override
                    public void visit(@NotNull FieldMember field) {
                        events.add("field.visit");
                    }

                    @Override
                    public DexConstantVisitor visitStaticValue(@NotNull Constant value) {
                        events.add("field.static");
                        return null;
                    }

                    @Override
                    public DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
                        events.add("field.annotation");
                        return recordingAnnotationVisitor(events, "field.annotation");
                    }

                    @Override
                    public void visitEnd() {
                        events.add("field.end");
                    }
                };
            }

            @Override
            public DexMethodVisitor visitMethod(@NotNull MethodMember method) {
                return new DexMethodVisitor() {
                    @Override
                    public void visit(@NotNull MethodMember method) {
                        events.add("method.visit");
                    }

                    @Override
                    public DexAnnotationVisitor visitAnnotation(@NotNull Annotation annotation) {
                        events.add("method.annotation");
                        return recordingAnnotationVisitor(events, "method.annotation");
                    }

                    @Override
                    public DexCodeVisitor visitCode(@NotNull Code code) {
                        events.add("method.code");
                        return null;
                    }

                    @Override
                    public void visitEnd() {
                        events.add("method.end");
                    }
                };
            }

            @Override
            public void visitEnd() {
                events.add("class.end");
            }
        });

        assertIterableEquals(List.of(
                "class.visit",
                "class.annotation",
                "field.visit",
                "field.static",
                "field.annotation",
                "field.annotation.visit:test/FieldAnn",
                "field.annotation.element:value",
                "field.annotation.end",
                "field.end",
                "method.visit",
                "method.annotation",
                "method.annotation.visit:test/MethodAnn",
                "method.annotation.element:flag",
                "method.annotation.end",
                "method.code",
                "method.end",
                "class.end"
        ), events);
    }

    @Test
    void constantVisitorsRecurseThroughNestedAnnotationsAndArrays() {
        Constant constant = new ArrayConstant(List.of(
                new IntConstant(1),
                new AnnotationConstant(new AnnotationPart(
                        Types.instanceTypeFromInternalName("test/Nested"),
                        orderedElements(Map.entry("value", new StringConstant("inner"))))),
                new ArrayConstant(List.of(new StringConstant("deep")))
        ));
        List<String> events = new ArrayList<>();

        DexTreeWalker.accept(constant, new DexConstantVisitor() {
            @Override
            public void visitConstant(@NotNull Constant constant) {
                events.add("root.const:" + constant.getClass().getSimpleName());
            }

            @Override
            public DexConstantVisitor visitArrayConstant(@NotNull ArrayConstant constant) {
                events.add("root.array");
                return new DexConstantVisitor() {
                    @Override
                    public void visitConstant(@NotNull Constant constant) {
                        events.add("elem.const:" + constant.getClass().getSimpleName());
                    }

                    @Override
                    public DexAnnotationVisitor visitAnnotationConstant(@NotNull AnnotationConstant constant) {
                        events.add("elem.annotation");
                        return new DexAnnotationVisitor() {
                            @Override
                            public void visit(@NotNull AnnotationPart annotation) {
                                events.add("nested.visit:" + annotation.type().internalName());
                            }

                            @Override
                            public DexConstantVisitor visitElement(@NotNull String name, @NotNull Constant value) {
                                events.add("nested.element:" + name);
                                return new DexConstantVisitor() {
                                    @Override
                                    public void visitConstant(@NotNull Constant constant) {
                                        events.add("nested.value.const:" + constant.getClass().getSimpleName());
                                    }

                                    @Override
                                    public void visitStringConstant(@NotNull StringConstant constant) {
                                        events.add("nested.value.string:" + constant.value());
                                    }

                                    @Override
                                    public void visitEnd() {
                                        events.add("nested.value.end");
                                    }
                                };
                            }

                            @Override
                            public void visitEnd() {
                                events.add("nested.end");
                            }
                        };
                    }

                    @Override
                    public DexConstantVisitor visitArrayConstant(@NotNull ArrayConstant constant) {
                        events.add("elem.array");
                        return new DexConstantVisitor() {
                            @Override
                            public void visitConstant(@NotNull Constant constant) {
                                events.add("deep.const:" + constant.getClass().getSimpleName());
                            }

                            @Override
                            public void visitStringConstant(@NotNull StringConstant constant) {
                                events.add("deep.string:" + constant.value());
                            }

                            @Override
                            public void visitEnd() {
                                events.add("deep.end");
                            }
                        };
                    }

                    @Override
                    public void visitIntConstant(@NotNull IntConstant constant) {
                        events.add("elem.int:" + constant.value());
                    }

                    @Override
                    public void visitEnd() {
                        events.add("elem.end");
                    }
                };
            }

            @Override
            public void visitEnd() {
                events.add("root.end");
            }
        });

        assertIterableEquals(List.of(
                "root.const:ArrayConstant",
                "root.array",
                "elem.const:IntConstant",
                "elem.int:1",
                "elem.end",
                "elem.const:AnnotationConstant",
                "elem.annotation",
                "nested.visit:test/Nested",
                "nested.element:value",
                "nested.value.const:StringConstant",
                "nested.value.string:inner",
                "nested.value.end",
                "nested.end",
                "elem.end",
                "elem.const:ArrayConstant",
                "elem.array",
                "deep.const:StringConstant",
                "deep.string:deep",
                "deep.end",
                "elem.end",
                "root.end"
        ), events);
    }

    private static DexAnnotationVisitor recordingAnnotationVisitor(List<String> events, String prefix) {
        return new DexAnnotationVisitor() {
            @Override
            public void visit(@NotNull AnnotationPart annotation) {
                events.add(prefix + ".visit:" + annotation.type().internalName());
            }

            @Override
            public DexConstantVisitor visitElement(@NotNull String name, @NotNull Constant value) {
                events.add(prefix + ".element:" + name);
                return null;
            }

            @Override
            public void visitEnd() {
                events.add(prefix + ".end");
            }
        };
    }

    private static DexConstantVisitor recordingConstantVisitor(List<String> events, String prefix) {
        return new DexConstantVisitor() {
            @Override
            public void visitConstant(@NotNull Constant constant) {
                events.add(prefix + ".const:" + constant.getClass().getSimpleName());
            }

            @Override
            public void visitIntConstant(@NotNull IntConstant constant) {
                events.add(prefix + ".int:" + constant.value());
            }

            @Override
            public void visitStringConstant(@NotNull StringConstant constant) {
                events.add(prefix + ".string:" + constant.value());
            }

            @Override
            public void visitEnd() {
                events.add(prefix + ".end");
            }
        };
    }

    private static ClassDefinition sampleDefinition() {
        ClassDefinition definition = new ClassDefinition(
                Types.instanceTypeFromInternalName("test/Visitor"),
                Types.OBJECT,
                AccessFlags.ACC_PUBLIC
        );
        definition.setSourceFile("Visitor.java");
        definition.setEnclosingClass(Types.instanceTypeFromInternalName("test/Outer"));
        definition.setEnclosingMethod(new MemberIdentifier("outer", "()V"));
        definition.addInnerClass(new InnerClass("test/Visitor$Inner", "test/Visitor", "Inner",
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC));
        definition.addAnnotation(annotation("test/ClassAnn", orderedElements(
                Map.entry("value", new StringConstant("class"))
        )));

        FieldMember field = new FieldMember("COUNT", Types.INT, AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC);
        field.setStaticValue(new IntConstant(7));
        field.addAnnotation(annotation("test/FieldAnn", orderedElements(
                Map.entry("value", new StringConstant("field"))
        )));
        definition.putField(field);

        MethodMember method = new MethodMember("call", Types.methodTypeFromDescriptor("()V"),
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC);
        method.addAnnotation(annotation("test/MethodAnn", orderedElements(
                Map.entry("flag", new IntConstant(1))
        )));
        method.setCode(sampleCode());
        definition.putMethod(method);

        return definition;
    }

    private static Code sampleCode() {
        Code code = new Code(0, 0, 2);
        Label start = new Label(0, 0);
        Label end = new Label(1, 2);
        Label handler = new Label(2, 3);
        Label exit = new Label(3, 4);

        InvokeCustomInstruction invokeCustom = new InvokeCustomInstruction(
                new Handle(Handle.KIND_INVOKE_STATIC,
                        Types.instanceTypeFromInternalName("test/Bootstrap"),
                        "bootstrap",
                        Types.methodTypeFromDescriptor("()V")),
                "callSite",
                Types.methodTypeFromDescriptor("()V"),
                List.of(new StringConstant("bootstrap"), new IntConstant(2))
        );

        code.addInstructions(List.of(
                start,
                new NopInstruction(),
                invokeCustom,
                end,
                new ReturnInstruction(),
                handler,
                new MoveExceptionInstruction(0),
                exit,
                new ReturnInstruction()
        ));
        code.addTryCatch(new TryCatch(start, end, List.of(
                new Handler(handler, Types.instanceType(RuntimeException.class)),
                new Handler(exit, null)
        )));
        code.setDebugInfo(new DebugInformation(
                List.of(new DebugInformation.LineNumber(start, 42)),
                List.of("ignored"),
                List.of(new DebugInformation.LocalVariable(0, "value", Types.INT, null, start, end))
        ));

        return code;
    }

    private static Annotation annotation(String internalName, LinkedHashMap<String, Constant> elements) {
        return new Annotation((byte) Annotation.VISIBILITY_RUNTIME,
                new AnnotationPart(Types.instanceTypeFromInternalName(internalName), elements));
    }

    @SafeVarargs
    private static LinkedHashMap<String, Constant> orderedElements(Map.Entry<String, Constant>... entries) {
        LinkedHashMap<String, Constant> elements = new LinkedHashMap<>();
        for (Map.Entry<String, Constant> entry : entries) {
            elements.put(entry.getKey(), entry.getValue());
        }
        return elements;
    }
}
