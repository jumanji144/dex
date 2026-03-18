package me.darknet.dex.convert;

import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.optimize.IrOptimizationContext;
import me.darknet.dex.convert.ir.optimize.IrOptimizer;
import me.darknet.dex.convert.ir.optimize.NoopIrOptimizer;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.util.Decompile;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.io.Input;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveResultInstruction;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.PackedSwitchInstruction;
import me.darknet.dex.tree.definitions.instructions.Result;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.definitions.instructions.ThrowInstruction;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests conversion of dex code models to Java bytecode.
 */
class DexConversionTest implements Opcodes {
    @Test
    void executesArithmeticBranchInvokeSwitchAndTryCatch() throws Exception {
        // Create a class with a variety of methods that test different control flow constructs and instructions.
        ClassDefinition cls = new ClassDefinition(
                Types.instanceTypeFromInternalName("test/IrExec"),
                Types.instanceType(Object.class),
                org.objectweb.asm.Opcodes.ACC_PUBLIC
        );
        cls.putMethod(method("arith", Types.methodTypeFromDescriptor("()I"), arithmeticCode(), ACC_PUBLIC | ACC_STATIC));
        cls.putMethod(method("branch", Types.methodTypeFromDescriptor("(I)I"), branchCode(), ACC_PUBLIC | ACC_STATIC));
        cls.putMethod(method("boxed", Types.methodTypeFromDescriptor("(I)Ljava/lang/Integer;"), boxedCode(), ACC_PUBLIC | ACC_STATIC));
        cls.putMethod(method("packed", Types.methodTypeFromDescriptor("(I)I"), packedSwitchCode(), ACC_PUBLIC | ACC_STATIC));
        cls.putMethod(method("catcher", Types.methodTypeFromDescriptor("()I"), tryCatchCode(), ACC_PUBLIC | ACC_STATIC));

        // Convert the dex class to Java bytecode and load it.
        DexFile dex = new DexFile(39, List.of(cls), new byte[0]);
        DexConversion conversion = new DexConversionIr();
        conversion.setWriterFactory(c -> new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS));
        byte[] bytecode = conversion.toClasses(dex).classes().get("test/IrExec");
        Class<?> loaded = new ByteArrayClassLoader().define("test.IrExec", bytecode);

        // Invoke the methods and verify that they return the expected results,
        // indicating that the control flow and instructions were correctly converted and executed.
        assertEquals(5, invokeStatic(loaded, "arith"));
        assertEquals(1, invokeStatic(loaded, "branch", 0));
        assertEquals(2, invokeStatic(loaded, "branch", 4));
        assertEquals(Integer.valueOf(9), invokeStatic(loaded, "boxed", 9));
        assertEquals(10, invokeStatic(loaded, "packed", 1));
        assertEquals(20, invokeStatic(loaded, "packed", 2));
        assertEquals(30, invokeStatic(loaded, "packed", 8));
        assertEquals(7, invokeStatic(loaded, "catcher"));
    }

    @Test
    void omitsReferenceStoreLoadForImmediateSingleUseReturn() {
        // Create a class with a method that returns an object that is only used immediately in the return instruction.
        // This should not require storing the object in a local variable, and should not emit redundant astore/aload instructions.
        ClassDefinition cls = new ClassDefinition(
                Types.instanceTypeFromInternalName("test/IrExecStack"),
                Types.instanceType(Object.class),
                ACC_PUBLIC
        );
        cls.putMethod(method("boxed", Types.methodTypeFromDescriptor("(I)Ljava/lang/Integer;"), boxedCode(),
                ACC_PUBLIC | ACC_STATIC));

        // Convert the dex class to Java bytecode and verify that there are no redundant astore/aload pairs for the reference being returned.
        DexFile dex = new DexFile(39, List.of(cls), new byte[0]);
        byte[] bytecode = Converters.IR.toClasses(dex).classes().get("test/IrExecStack");

        // Analyze the bytecode of the boxed method to find any astore/aload instructions that operate on reference variables.
        List<Integer> referenceVarOps = new ArrayList<>();
        new ClassReader(bytecode).accept(new ClassVisitor(ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals("boxed") || !descriptor.equals("(I)Ljava/lang/Integer;")) return null;
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitVarInsn(int opcode, int varIndex) {
                        if (opcode == ALOAD || opcode == ASTORE) {
                            referenceVarOps.add(opcode);
                        }
                    }
                };
            }
        }, 0);
        assertTrue(referenceVarOps.isEmpty(), "boxed should not emit redundant astore/aload pairs: " + referenceVarOps);
    }

    @Test
    void createsOneOptimizerPerWholeDexSessionAndRunsProgramPhaseBeforeMethodPhase() {
        // Setup dummy classes with a couple of methods to convert.
        ClassDefinition first = new ClassDefinition(
                Types.instanceTypeFromInternalName("test/IrSessionOne"),
                Types.instanceType(Object.class),
                ACC_PUBLIC
        );
        first.putMethod(method("left", Types.methodTypeFromDescriptor("()I"), arithmeticCode(), ACC_PUBLIC | ACC_STATIC));
        first.putMethod(method("right", Types.methodTypeFromDescriptor("()I"), arithmeticCode(), ACC_PUBLIC | ACC_STATIC));

        ClassDefinition second = new ClassDefinition(
                Types.instanceTypeFromInternalName("test/IrSessionTwo"),
                Types.instanceType(Object.class),
                ACC_PUBLIC
        );
        second.putMethod(method("other", Types.methodTypeFromDescriptor("()I"), arithmeticCode(), ACC_PUBLIC | ACC_STATIC));

        // Set up a custom optimizer factory that verifies it is only called once for the whole dex,
        // that it receives the correct classes in the context, and that program-scope optimization runs before method-scope optimization.
        DexConversionIr conversion = new DexConversionIr();
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicBoolean programOptimized = new AtomicBoolean();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        conversion.setOptimizerFactory(context -> {
            factoryCalls.incrementAndGet();
            assertEquals(IrOptimizationContext.ScopeKind.WHOLE_DEX, context.scopeKind());
            assertEquals(List.of(first, second), context.classes());
            return new IrOptimizer() {
                @Override
                public void optimizeProgram(@NotNull IrOptimizationContext context) {
                    events.add("program");
                    programOptimized.set(true);
                }

                @Override
                public void optimizeMethod(@NotNull IrOptimizationContext context, @NotNull IrMethod method) {
                    assertTrue(programOptimized.get(), "optimizeProgram should run before optimizeMethod");
                    events.add(method.source().getOwner().internalName() + "." + method.source().getName());
                }
            };
        });

        // Converting the dex file should trigger the optimizer factory and run the optimizations,
        // which we verify through the events list and factory call count.
        DexFile dex = new DexFile(39, List.of(first, second), new byte[0]);
        ConversionResult result = conversion.toClasses(dex);
        assertTrue(result.errors().isEmpty(), () -> "Unexpected conversion errors: " + result.errors());
        assertEquals(1, factoryCalls.get());
        assertEquals("program", events.get(0));
        assertEquals(4, events.size());
    }

    @Test
    void exposesSingleClassScopeForDirectClassConversion() {
        // Dummy class + method
        ClassDefinition cls = new ClassDefinition(
                Types.instanceTypeFromInternalName("test/IrSingleScope"),
                Types.instanceType(Object.class),
                ACC_PUBLIC
        );
        cls.putMethod(method("value", Types.methodTypeFromDescriptor("()I"), arithmeticCode(), ACC_PUBLIC | ACC_STATIC));

        // Set up a custom optimizer factory that verifies it receives a single-class scope context with
        // the correct class when converting a single class directly.
        DexConversionIr conversion = new DexConversionIr();
        AtomicReference<IrOptimizationContext.ScopeKind> scope = new AtomicReference<>();
        conversion.setOptimizerFactory(context -> {
            scope.set(context.scopeKind());
            assertEquals(List.of(cls), context.classes());
            return new NoopIrOptimizer();
        });

        // Converting the class directly should trigger the optimizer factory with a single-class scope context.
        byte[] bytecode = conversion.toJavaClass(cls);
        assertNotNull(bytecode);
        assertEquals(IrOptimizationContext.ScopeKind.SINGLE_CLASS, scope.get());
    }

    @Test
    void customOptimizerCanInspectSiblingIrMethodsThroughSessionContext() throws Exception {
        // Dummy class + methods
        ClassDefinition cls = new ClassDefinition(
                Types.instanceTypeFromInternalName("test/IrSiblingScope"),
                Types.instanceType(Object.class),
                ACC_PUBLIC
        );
        cls.putMethod(method("helper", Types.methodTypeFromDescriptor("()I"), arithmeticCode(), ACC_PUBLIC | ACC_STATIC));
        cls.putMethod(method("caller", Types.methodTypeFromDescriptor("()I"), arithmeticCode(), ACC_PUBLIC | ACC_STATIC));

        // Set up a custom optimizer factory that replaces the body of caller with a constant,
        // but only after verifying it can see the helper method in the session context.
        //
        // This tests that the optimizer can inspect sibling methods in the same class through the session context,
        // which is important for optimizations that need to analyze multiple methods together.
        int ourConstant = 67;
        DexConversionIr conversion = new DexConversionIr();
        conversion.setOptimizerFactory(context -> new IrOptimizer() {
            @Override
            public void optimizeMethod(@NotNull IrOptimizationContext currentContext, @NotNull IrMethod method) {
                if (!method.source().getName().equals("caller"))
                    return;
                IrMethod helper = currentContext.getMethod(method.source().getOwner(), "helper", "()I");
                assertNotNull(helper);
                assertNotSame(helper, method);
                assertTrue(currentContext.getMethods(method.source().getOwner()).contains(helper));
                replaceFirstPureOpWithIntConstant(method, ourConstant);
            }
        });

        // Converting the class should trigger the optimizer, which should verify it can see the sibling method
        // and then replace the caller method body with a constant return.
        // We verify this by invoking both methods and checking their outputs.
        byte[] bytecode = conversion.toJavaClass(cls);
        Class<?> loaded = new ByteArrayClassLoader().define("test.IrSiblingScope", bytecode);
        assertEquals(5, invokeStatic(loaded, "helper"));
        assertEquals(ourConstant, invokeStatic(loaded, "caller"));
    }

    @Test
    void sharedConverterDoesNotLeakSessionStateAcrossConcurrentConversions() {
        // Set up custom optimizer factory that replaces the body of a method with a constant based on the class name,
        DexConversionIr conversion = new DexConversionIr();
        conversion.setOptimizerFactory(context -> new IrOptimizer() {
            @Override
            public void optimizeMethod(@NotNull IrOptimizationContext currentContext, @NotNull IrMethod method) {
                if (!method.source().getName().equals("value"))
                    return;
                String internalName = currentContext.classes().getFirst().getType().internalName();
                int expected = Integer.parseInt(internalName.substring("test/Parallel".length()));
                replaceFirstPureOpWithIntConstant(method, expected);
            }
        });

        // Create multiple classes with a method that returns a constant, and convert them in parallel using the same converter instance.
        int max = 1000;
        List<Integer> values = IntStream.range(0, max)
                .parallel()
                .mapToObj(index -> {
                    try {
                        // Create classes 'Parallel0', 'Parallel1', ..., 'Parallel15' with a method
                        // that returns a constant based on the class name.
                        String internalName = "test/Parallel" + index;
                        ClassDefinition cls = new ClassDefinition(
                                Types.instanceTypeFromInternalName(internalName),
                                Types.instanceType(Object.class),
                                ACC_PUBLIC
                        );
                        cls.putMethod(method("value", Types.methodTypeFromDescriptor("()I"), arithmeticCode(), ACC_PUBLIC | ACC_STATIC));
                        byte[] bytecode = conversion.toJavaClass(cls);

                        // Invoke the method and store the result.
                        Class<?> loaded = new ByteArrayClassLoader().define(internalName.replace('/', '.'), bytecode);
                        return (Integer) invokeStatic(loaded, "value");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();

        // We should get back the expected constants for each class in the range.
        // This verifies that the converter does not leak session state across concurrent conversions,
        // and that each conversion is properly isolated.
        assertEquals(IntStream.range(0, max).boxed().toList(), values);
    }

    /**
     * These are for specific cases where we had problems with emitting invalid code constructs.
     * This would manifest as the decompiler being unable to represent the converted Java bytecode
     * in its decompiled output. We just want to make sure we don't get back into that state.
     */
     @Nested
     class Regressions {
         @Test
         void intMathCatchBlockDecompilesWithoutFailureStub() throws Exception {
             assertSampleDecompilesWithoutFailureStub("107-int-math2", "Main");
         }

         @Test
         void compilerRegressionMonitorDecompilesWithoutFailureStub() throws Exception {
             assertSampleDecompilesWithoutFailureStub("123-compiler-regressions-mt", "B17689750TestMonitor");
         }

         @Test
         void npeSampleDecompilesWithoutFailureStub() throws Exception {
             assertSampleDecompilesWithoutFailureStub("122-npe", "Main");
         }
    }

    private static MethodMember method(String name, MethodType type, Code code, int access) {
        MethodMember method = new MethodMember(name, type, access);
        method.setCode(code);
        return method;
    }

    private static ClassDefinition loadSampleClass(String sample, String owner) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path path = cwd.resolve("test-data").resolve("samples").resolve(sample).resolve("classes.dex");
        if (!Files.exists(path)) {
            path = cwd.resolve("..").resolve("test-data").resolve("samples").resolve(sample).resolve("classes.dex").normalize();
        }
        Input dexInput = Input.wrap(Files.readAllBytes(path));
        DexHeaderCodec codec = DexHeader.CODEC;
        DexHeader header = codec.read(dexInput);
        DexMap map = header.map();
        DexFile dexFile = DexFile.CODEC.map(header, map);
        for (ClassDefinition cls : dexFile.definitions()) {
            if (cls.getType().internalName().equals(owner)) {
                return cls;
            }
        }
        throw new IllegalStateException("Missing class " + owner + " in " + path);
    }

    private static void assertSampleDecompilesWithoutFailureStub(String sample, String owner) throws Exception {
        ClassDefinition cls = loadSampleClass(sample, owner);
        byte[] bytecode = Converters.IR.toJavaClass(cls);
        String decompiled = Decompile.decompile(owner, bytecode);
        assertFalse(decompiled.contains("Decompilation failed"),
                () -> "CFR emitted a decompilation failure stub for " + owner + " from sample " + sample
                + ":\n" + decompiled + "\n\nBytecode:\n" + Decompile.bytecode(bytecode));
    }

    /**
     * @return Method code that will return {@code 5}.
     */
    private static Code arithmeticCode() {
        // Pseudo-code:
        // int a = 2;
        // int b = 3;
        // int c = a + b;
        // return c;
        return code(3, 0,
                new ConstInstruction(0, 2),
                new ConstInstruction(1, 3),
                new BinaryInstruction(me.darknet.dex.file.instructions.Opcodes.ADD_INT, 2, 0, 1),
                new ReturnInstruction(2));
    }

    /**
     * @return Method code that will return {@code 2} if the input is greater than zero, and {@code 1} otherwise.
     */
    private static Code branchCode() {
        // Pseudo-code:
        // if (input > 0) {
        //   return 2;
        // } else {
        //   return 1;
        // }
        Label elseLabel = new Label();
        Label endLabel = new Label();
        return code(3, 1,
                new BranchZeroInstruction(0, 2, elseLabel),
                new ConstInstruction(0, 2),
                new GotoInstruction(endLabel),
                elseLabel,
                new ConstInstruction(0, 1),
                endLabel,
                new ReturnInstruction(0));
    }

    /**
     * @return Method code that will box an input int.
     */
    private static Code boxedCode() {
        // Pseudo-code:
        // return Integer.valueOf(input);
        return code(2, 1,
                new InvokeInstruction(Invoke.STATIC, Types.instanceType(Integer.class), "valueOf",
                        Types.methodTypeFromDescriptor("(I)Ljava/lang/Integer;"), 1),
                new MoveResultInstruction(Result.OBJECT, 0),
                new ReturnInstruction(0, Return.OBJECT));
    }

    /**
     * @return Method code that will return:
     * {@code 10} if the input is {@code 1},
     * {@code 20} if the input is {@code 2},
     * and {@code 30} otherwise, using a packed switch.
     */
    private static Code packedSwitchCode() {
        // Pseudo-code:
        // switch (input) {
        //   case 1: return 10;
        //   case 2: return 20;
        //   default: return 30;
        // }
        Label caseOne = new Label();
        Label caseTwo = new Label();
        return code(3, 1,
                new PackedSwitchInstruction(2, 1, List.of(caseOne, caseTwo)),
                new ConstInstruction(0, 30),
                new ReturnInstruction(0),
                caseOne,
                new ConstInstruction(0, 10),
                new ReturnInstruction(0),
                caseTwo,
                new ConstInstruction(0, 20),
                new ReturnInstruction(0));
    }

    /**
     * @return Method code that will return {@code 7} by throwing and catching an exception.
     */
    private static Code tryCatchCode() {
        // Pseudo-code:
        // try {
        //   throw new IllegalStateException();
        //   return 0;
        // } catch (Throwable t) {
        //   return 7;
        // }

        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Code code = code(2, 0,
                start,
                new NewInstanceInstruction(0, Types.instanceType(IllegalStateException.class)),
                new InvokeInstruction(Invoke.DIRECT, Types.instanceType(IllegalStateException.class), "<init>",
                        Types.methodTypeFromDescriptor("()V"), 0),
                new ThrowInstruction(0),
                end,
                new ConstInstruction(0, 0),
                new ReturnInstruction(0),
                handler,
                new MoveExceptionInstruction(1),
                new ConstInstruction(0, 7),
                new ReturnInstruction(0));
        code.addTryCatch(new TryCatch(start, end, List.of(new Handler(handler, Types.instanceType(Throwable.class)))));
        return code;
    }

    private static Code code(int registers, int in, Instruction... instructions) {
        Code code = new Code(in, 0, registers);
        List<Instruction> assigned = assignLabels(List.of(instructions));
        code.addInstructions(assigned);
        return code;
    }

    private static List<Instruction> assignLabels(List<Instruction> instructions) {
        int offset = 0;
        int index = 0;
        List<Instruction> out = new ArrayList<>(instructions.size());
        for (Instruction instruction : instructions) {
            if (instruction instanceof Label label) {
                label.index(index++);
                label.position(offset);
            } else {
                offset += instruction.byteSize();
            }
            out.add(instruction);
        }
        return out;
    }

    private static void replaceFirstPureOpWithIntConstant(IrMethod method, int value) {
        for (var block : method.blocks()) {
            for (IrStmt statement : block.statements()) {
                if (statement instanceof IrOp op && op.pure()) {
                    op.replaceWith(new IrConstant(-1, Types.INT, value, value == 0));
                    return;
                }
            }
        }
        fail("Expected a pure IR op in " + method.source().getOwner().internalName() + "." + method.source().getName());
    }

    private static Object invokeStatic(Class<?> owner, String name, Object... args) throws Exception {
        Class<?>[] parameterTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            parameterTypes[i] = args[i] instanceof Integer ? int.class : args[i].getClass();
        }
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        return method.invoke(null, args);
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
