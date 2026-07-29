package me.darknet.dex.convert;

import me.darknet.dex.codecs.DexHeaderCodec;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.optimize.IrOptimizationContext;
import me.darknet.dex.convert.ir.optimize.IrOptimizer;
import me.darknet.dex.convert.ir.optimize.NoopIrOptimizer;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy;
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
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction;
import me.darknet.dex.tree.definitions.instructions.MoveResultInstruction;
import me.darknet.dex.tree.definitions.instructions.NewArrayInstruction;
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
    void convertsFullSampleCorpusWithoutVerifierFailures() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path path = cwd.resolve("test-data/classes.dex");
        if (!Files.exists(path))
            path = cwd.resolve("..").resolve("test-data/classes.dex").normalize();

        Input dexInput = Input.wrap(Files.readAllBytes(path));
        DexHeader header = DexHeader.CODEC.read(dexInput);
        DexFile dexFile = DexFile.CODEC.map(header, header.map());
        ConversionResult result = Converters.IR.toClasses(dexFile);

        assertTrue(result.errors().isEmpty(), () -> "Sample corpus verifier failures: " + result.errors());
        assertEquals(dexFile.definitions().size(), result.classes().size());
    }

    @Test
    void executesNestedArrayConstructionAndStores() throws Exception {
        ClassDefinition cls = new ClassDefinition(
                Types.instanceTypeFromInternalName("test/NestedArray"), Types.instanceType(Object.class), ACC_PUBLIC);
        cls.putMethod(method("make", Types.methodTypeFromDescriptor("()[[I"),
                code(4, 0,
                        new ConstInstruction(0, 1),
                        new NewArrayInstruction(1, 0, Types.arrayTypeFromDescriptor("[[I")),
                        new NewArrayInstruction(2, 0, Types.arrayTypeFromDescriptor("[I")),
                        new ConstInstruction(3, 0),
                        new ArrayInstruction(me.darknet.dex.file.instructions.Opcodes.APUT_OBJECT
                                - me.darknet.dex.file.instructions.Opcodes.AGET, 2, 1, 3),
                        new ReturnInstruction(1, Return.OBJECT)),
                ACC_PUBLIC | ACC_STATIC));

        DexFile dex = new DexFile(39, List.of(cls));
        byte[] bytecode = Converters.IR.toClasses(dex).classes().get("test/NestedArray");
        Class<?> loaded = new ByteArrayClassLoader().define("test.NestedArray", bytecode);
        Object result = invokeStatic(loaded, "make");
        assertEquals(1, java.lang.reflect.Array.getLength(result));
        assertTrue(java.lang.reflect.Array.get(result, 0) instanceof int[],
                () -> result.getClass() + " inner=" + java.lang.reflect.Array.get(result, 0));
    }

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
        cls.putMethod(method("divide", Types.methodTypeFromDescriptor("()I"), divisionCatchCode(), ACC_PUBLIC | ACC_STATIC));

        // Convert the dex class to Java bytecode and load it.
        DexFile dex = new DexFile(39, List.of(cls));
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
        assertEquals(7, invokeStatic(loaded, "divide"));
    }

    @Test
    void keepsTypedUnknownFallbacksLoadableAndDiagnosable() {
        ClassDefinition cls = new ClassDefinition(
                Types.instanceTypeFromInternalName("test/UnknownFallback"),
                Types.instanceType(Object.class), ACC_PUBLIC);
        cls.putMethod(method("value", Types.methodTypeFromDescriptor("(I)I"),
                code(1, 1,
                        new BinaryInstruction(me.darknet.dex.file.instructions.Opcodes.ADD_INT, 0, 0, 1),
                        new ReturnInstruction(0)), ACC_PUBLIC | ACC_STATIC));

        ConversionResult result = Converters.IR.toClasses(new DexFile(39, List.of(cls)));
        assertTrue(result.errors().isEmpty(), () -> "Unexpected conversion errors: " + result.errors());
        assertTrue(result.classes().containsKey("test/UnknownFallback"));
        assertFalse(result.diagnostics().getOrDefault("test/UnknownFallback", List.of()).isEmpty());
        Decompile.verify(result.classes().get("test/UnknownFallback"));
    }

    @Test
    void materializesReferenceStoreLoadForImmediateSingleUseReturn() {
        // Create a class with a method that returns an object that is only used immediately in the return instruction.
        // Local-first lowering deliberately materializes the object even when its
        // only use is the return instruction.
        ClassDefinition cls = new ClassDefinition(
                Types.instanceTypeFromInternalName("test/IrExecStack"),
                Types.instanceType(Object.class),
                ACC_PUBLIC
        );
        cls.putMethod(method("boxed", Types.methodTypeFromDescriptor("(I)Ljava/lang/Integer;"), boxedCode(),
                ACC_PUBLIC | ACC_STATIC));

        // Convert the dex class to Java bytecode and verify that the reference is
        // materialized through a local.
        DexFile dex = new DexFile(39, List.of(cls));
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
        assertFalse(referenceVarOps.isEmpty(), "boxed should materialize its returned reference: " + referenceVarOps);
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
        DexFile dex = new DexFile(39, List.of(first, second));
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
         void realFileTransferPairingCodeFusesSingleUseArrayReads() throws Exception {
             String owner = "com/example/imageserver/transfer/IdentityStore";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("pairingCode(");
             int end = decompiled.indexOf("public static byte[] sessionTranscript", start + 1);
             assertTrue(start >= 0 && end > start,
                     () -> "Missing IdentityStore.pairingCode in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             Decompile.verify(bytecode);
             assertTrue(method.contains("Arrays.sort(objectArray)"), method);
             assertTrue(method.contains("Arrays.sort(byArrayArray"), method);
             assertTrue(method.contains("MessageDigest.getInstance(\"SHA-256\").digest(IdentityStore.sessionTranscript"), method);
             assertFalse(method.contains("$ExternalSyntheticLambda1"), method);
             assertFalse(method.contains("Object object = objectArray[0]"), method);
             assertFalse(method.contains("byte[] byArray3 = byArrayArray[0]"), method);
             assertFalse(method.contains("byte[] byArray4 ="), method);
             Class<?> loaded = new ByteArrayClassLoader().define(owner.replace('/', '.'), bytecode);
             assertEquals(invokeStatic(loaded, "pairingCode", "first", "second",
                     new byte[] {1, 2}, new byte[] {3, 4}),
                     invokeStatic(loaded, "pairingCode", "second", "first",
                             new byte[] {3, 4}, new byte[] {1, 2}));
         }

         @Test
         void realFileTransferSessionTranscriptReconstructsComparatorLambda() throws Exception {
             String owner = "com/example/imageserver/transfer/IdentityStore";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public static byte[] sessionTranscript(");
             int end = decompiled.indexOf("public X509Certificate", start + 1);
             assertTrue(start >= 0 && end > start,
                     () -> "Missing IdentityStore.sessionTranscript in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             Decompile.verify(bytecode);
             assertTrue(method.contains("Arrays.sort"), method);
             assertTrue(method.contains("write(((String)objectArray[0]).getBytes(\"UTF-8\"))"), method);
             assertTrue(method.contains("write(((String)objectArray[1]).getBytes(\"UTF-8\"))"), method);
             assertTrue(method.contains("write(byArrayArray[0])"), method);
             assertTrue(method.contains("write(byArrayArray[1])"), method);
             assertFalse(method.matches("(?s).*byte\\[\\] [A-Za-z0-9]+ = .*getBytes.*"), method);
             assertFalse(method.contains("$ExternalSyntheticLambda0"), method);
             Class<?> loaded = new ByteArrayClassLoader().define(owner.replace('/', '.'), bytecode);
             byte[] first = (byte[]) invokeStatic(loaded, "sessionTranscript", "first", "second",
                     new byte[] {1, 2}, new byte[] {3, 4});
             byte[] second = (byte[]) invokeStatic(loaded, "sessionTranscript", "second", "first",
                     new byte[] {3, 4}, new byte[] {1, 2});
             assertArrayEquals(first, second);
         }

         @Test
         void realFileTransferGetPeersReconstructsMethodReferenceLambda() throws Exception {
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");
             String owner = "com/example/imageserver/transfer/TransferService";
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             ConversionResult conversionResult = conversion.toClasses(dex);
             assertTrue(conversionResult.errors().isEmpty(), conversionResult.errors()::toString);
             byte[] bytecode = conversionResult.classes().get(owner);
             assertNotNull(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.lastIndexOf("getPeers(");
             int end = decompiled.indexOf("public", start + 1);
             assertTrue(start >= 0 && end > start,
                     () -> "Missing TransferService.getPeers in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             Decompile.verify(bytecode);
             assertFalse(method.contains("$ExternalSyntheticLambda10"), method);
             assertTrue(method.contains("Comparator.comparing"), method);
         }

         @Test
         void realFileTransferAggressivePolicyIsOptInDeterministicAndVerifiable() throws Exception {
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");
             String[] targets = {
                     "com/example/imageserver/transfer/TransferFiles",
                     "com/example/imageserver/transfer/TransferService",
                     "com/example/imageserver/transfer/IdentityStore",
                     "com/example/imageserver/transfer/PairingStore"
             };

             DexConversionIr defaults = new DexConversionIr();
             assertEquals(JvmLoweringPolicy.DETERMINISTIC_LOCAL, defaults.getJvmLoweringPolicy());
             DexConversionIr explicitLocal = new DexConversionIr();
             explicitLocal.setJvmLoweringPolicy(JvmLoweringPolicy.DETERMINISTIC_LOCAL);
             DexConversionIr aggressive = new DexConversionIr();
             aggressive.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);

             ConversionResult first = aggressive.toClasses(dex);
             ConversionResult second = aggressive.toClasses(dex);
             assertTrue(first.errors().isEmpty(), first.errors()::toString);
             assertTrue(second.errors().isEmpty(), second.errors()::toString);
             for (String target : targets) {
                 byte[] firstBytes = first.classes().get(target);
                 byte[] secondBytes = second.classes().get(target);
                 assertNotNull(firstBytes, target);
                 assertArrayEquals(firstBytes, secondBytes, target);
                 Decompile.verify(firstBytes);
             }
			String pairing = Decompile.decompile(targets[3], first.classes().get(targets[3]));
			assertFalse(pairing.contains("Decompilation failed") || pairing.contains("Unable to fully structure code"), pairing);
			assertFalse(pairing.substring(pairing.indexOf("public boolean isKnown"),
					pairing.indexOf("public boolean isPaired")).contains("StringBuilder"), pairing);
			String files = Decompile.decompile(targets[0], first.classes().get(targets[0]));
			assertFalse(files.contains("Decompilation failed") || files.contains("Unable to fully structure code"), files);
			String service = Decompile.decompile(targets[1], first.classes().get(targets[1]));
			assertTrue(service.contains("NotificationCompat.Builder") && service.contains(".build()"), service);
			int startNsdStart = service.indexOf("private void startNsd");
			int startNsdEnd = service.indexOf("private void updateNotification", startNsdStart + 1);
			assertTrue(startNsdStart >= 0 && startNsdEnd > startNsdStart, service);
			String startNsd = service.substring(startNsdStart, startNsdEnd);
			assertFalse(startNsd.contains("NsdServiceInfo nsdServiceInfo"), startNsd);
			String identity = Decompile.decompile(targets[2], first.classes().get(targets[2]));
			assertTrue(identity.contains("StringBuilder") && identity.contains("String.format"), identity);

             assertTrue(first.diagnostics().values().stream().flatMap(List::stream)
                     .anyMatch(diagnostic -> diagnostic.kind() == ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION));
             assertTrue(first.diagnostics().values().stream().flatMap(List::stream)
                             .anyMatch(diagnostic -> diagnostic.kind() == ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION
                                     && diagnostic.message().contains("single-use elimination")),
                     () -> "Expected aggressive single-use elimination diagnostics: " + first.diagnostics());
             ConversionResult local = explicitLocal.toClasses(dex);
             ConversionResult defaultResult = defaults.toClasses(dex);
             assertTrue(local.errors().isEmpty(), local.errors()::toString);
             assertTrue(defaultResult.errors().isEmpty(), defaultResult.errors()::toString);
             assertTrue(local.classes().keySet().containsAll(first.classes().keySet()));
             for (String target : targets)
                 assertArrayEquals(defaultResult.classes().get(target), local.classes().get(target), target);
         }

         @Test
         void realFileTransferAggressivePlansResourceLifecycles() throws Exception {
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
             ConversionResult result = conversion.toClasses(dex);
             assertTrue(result.errors().isEmpty(), result.errors()::toString);
             List<ConversionDiagnostic> diagnostics = result.diagnostics().values().stream()
                     .flatMap(List::stream)
                     .toList();
             List<ConversionDiagnostic> resourcePlans = diagnostics.stream()
                     .filter(diagnostic -> diagnostic.kind() == ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION
                             && diagnostic.message().contains("resource lifecycle plan"))
                     .toList();
             assertFalse(resourcePlans.isEmpty(), () -> "No aggressive resource lifecycle plan was selected: " + diagnostics);
             assertTrue(resourcePlans.stream().anyMatch(diagnostic -> diagnostic.method().contains("handleConnection")),
                     () -> "Expected a relaxed nested-resource plan for handleConnection: " + resourcePlans);
             assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.kind() == ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION
                             && diagnostic.message().contains("cleanup-tail normalization")),
                     () -> "Expected at least one accepted aggressive cleanup tail: " + diagnostics);
             assertNotNull(result.classes().get("com/example/imageserver/transfer/TransferFiles"));
             assertNotNull(result.classes().get("com/example/imageserver/transfer/TransferService"));
         }

         @Test
         void realFileTransferAggressiveLoopPlansRemainVerifierSafe() throws Exception {
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
             ConversionResult result = conversion.toClasses(dex);
             assertTrue(result.errors().isEmpty(), result.errors()::toString);
             List<ConversionDiagnostic> loopDiagnostics = result.diagnostics().values().stream()
                     .flatMap(List::stream)
                     .filter(diagnostic -> diagnostic.kind() == ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION
                             && diagnostic.message().contains("loop shape"))
                     .toList();
             assertFalse(loopDiagnostics.isEmpty(), () -> "No aggressive loop-shape plan was selected: " + result.diagnostics());
             Decompile.verify(result.classes().get("com/example/imageserver/transfer/TransferService"));
             Decompile.verify(result.classes().get("com/example/imageserver/transfer/IdentityStore"));
         }

         @Test
         void realFileTransferAggressiveReceiverChainsRemainStructured() throws Exception {
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
             ConversionResult result = conversion.toClasses(dex);
             assertTrue(result.errors().isEmpty(), result.errors()::toString);
             assertTrue(result.diagnostics().values().stream().flatMap(List::stream)
                             .anyMatch(diagnostic -> diagnostic.kind() == ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION
                                     && diagnostic.message().contains("receiver-chain cleanup")),
                     () -> "No aggressive receiver-chain plan was applied: " + result.diagnostics());

             String serviceOwner = "com/example/imageserver/transfer/TransferService";
             byte[] serviceBytes = result.classes().get(serviceOwner);
             byte[] filesBytes = result.classes().get("com/example/imageserver/transfer/TransferFiles");
             assertNotNull(serviceBytes);
             assertNotNull(filesBytes);
             Decompile.verify(serviceBytes);
             Decompile.verify(filesBytes);

             String service = Decompile.decompile(serviceOwner, serviceBytes);
             int notificationStart = service.indexOf("private Notification buildNotification");
             int notificationEnd = service.indexOf("private void createNotificationChannel", notificationStart + 1);
             assertTrue(notificationStart >= 0 && notificationEnd > notificationStart, service);
             String notification = service.substring(notificationStart, notificationEnd);
             assertTrue(notification.contains("NotificationCompat.Builder")
                             && notification.contains("setSmallIcon")
                             && notification.contains(".build()"), notification);
             assertFalse(notification.contains("Unable to fully structure code")
                             || notification.contains("Decompilation failed"), notification);

             String files = Decompile.decompile("com/example/imageserver/transfer/TransferFiles", filesBytes);
             int createStart = files.indexOf("public static DocumentFile createTemp");
             int createEnd = files.indexOf("public static String uniqueName", createStart + 1);
             assertTrue(createStart >= 0 && createEnd > createStart, files);
             String createTemp = files.substring(createStart, createEnd);
             assertTrue(createTemp.contains("createFile") && createTemp.contains("sanitizeName"), createTemp);
             assertFalse(createTemp.contains("Unable to fully structure code")
                             || createTemp.contains("Decompilation failed"), createTemp);

             String bytecode = Decompile.bytecode(serviceBytes);
             int sendStart = bytecode.indexOf("private sendOffer");
             int sendEnd = bytecode.indexOf("// access flags", sendStart + 1);
             if (sendEnd < 0) sendEnd = bytecode.length();
             String sendOffer = sendStart >= 0 ? bytecode.substring(sendStart, sendEnd) : "";
             int data = sendOffer.indexOf("INVOKESTATIC com/example/imageserver/transfer/TransferProtocol.data");
             int write = sendOffer.indexOf("INVOKESTATIC com/example/imageserver/transfer/TransferProtocol.writeFrame", data + 1);
             assertTrue(data >= 0 && write > data, sendOffer);
             boolean sendOfferFallback = result.diagnostics().values().stream().flatMap(List::stream)
                     .anyMatch(diagnostic -> diagnostic.method().contains("sendOffer")
                             && diagnostic.kind() == ConversionDiagnostic.Kind.UNSAFE_OPTIMIZATION
                             && diagnostic.message().contains("retried deterministic lowering"));
             if (!sendOfferFallback)
                 assertFalse(sendOffer.substring(data, write).contains("ASTORE"), sendOffer);
         }

         @Test
         void realFileTransferAggressiveResourceMethodsHaveNoCfrFailureMarkers() throws Exception {
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
             ConversionResult result = conversion.toClasses(loadSampleDex("REAL-FileTransfer", "classes5.dex"));
             assertTrue(result.errors().isEmpty(), result.errors()::toString);
             String owner = "com/example/imageserver/transfer/TransferService";
             String decompiled = Decompile.decompile(owner, result.classes().get(owner));
             assertFalse(decompiled.contains("Decompilation failed")
                             || decompiled.contains("Exception decompiling"), decompiled);
             for (String method : List.of("sendOffer", "receiveOffer", "handleConnection"))
                 assertTrue(result.diagnostics().values().stream().flatMap(List::stream)
                                 .anyMatch(diagnostic -> diagnostic.method().contains(method)),
                         () -> "Missing diagnostics for " + method + ": " + result.diagnostics());
         }

         @Test
         void realFileTransferAggressiveReceiveOfferHasReadableResourceAndCleanupShape() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");

             DexConversionIr deterministicConversion = new DexConversionIr();
             deterministicConversion.setJvmLoweringPolicy(JvmLoweringPolicy.DETERMINISTIC_LOCAL);
             ConversionResult deterministic = deterministicConversion.toClasses(dex);
             assertTrue(deterministic.errors().isEmpty(), deterministic.errors()::toString);

             DexConversionIr aggressiveConversion = new DexConversionIr();
             aggressiveConversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
             ConversionResult aggressive = aggressiveConversion.toClasses(loadSampleDex("REAL-FileTransfer", "classes5.dex"));
             assertTrue(aggressive.errors().isEmpty(), aggressive.errors()::toString);

             byte[] aggressiveBytes = aggressive.classes().get(owner);
             assertNotNull(aggressiveBytes);
             Decompile.verify(aggressiveBytes);
             String deterministicSource = Decompile.decompile(owner, deterministic.classes().get(owner));
             String aggressiveSource = Decompile.decompile(owner, aggressiveBytes);
             DecompilationQualityReport.MethodMetrics baseline = DecompilationQualityReport.capture(
                     owner, "receiveOffer", deterministic.classes().get(owner), deterministicSource,
                     deterministic.diagnostics().values().stream().flatMap(List::stream).toList());
             DecompilationQualityReport.MethodMetrics candidate = DecompilationQualityReport.capture(
                     owner, "receiveOffer", aggressiveBytes, aggressiveSource,
                     aggressive.diagnostics().values().stream().flatMap(List::stream).toList());
             String method = DecompilationQualityReport.extractMethod(aggressiveSource, "receiveOffer");

             assertFalse(candidate.aggressiveFallback(),
                     () -> "receiveOffer aggressive lowering unexpectedly fell back: " + candidate.diagnostics());
             assertTrue(candidate.improvedOver(baseline),
                     () -> "Expected receiveOffer aggressive output to improve over deterministic output.\n"
                             + baseline.summary() + "\n" + candidate.summary() + "\n" + method);
             assertTrue(candidate.failureMarkers().isEmpty(),
                     () -> "receiveOffer retained CFR failure markers:\n" + method);
             assertEquals(0, candidate.syntheticBlockCount(),
                     () -> "receiveOffer retained synthetic block scaffolding:\n" + method);
             assertEquals(0, candidate.syntheticLabelCount(),
                     () -> "receiveOffer retained synthetic lbl scaffolding:\n" + method);
             assertFalse(candidate.hasInfiniteLoop(),
                     () -> "receiveOffer retained synthetic while(true) recovery:\n" + method);
             assertTrue(method.contains("TransferProtocol.readData"), method);
             assertTrue(method.contains(".write("), method);
             assertTrue(method.contains("MessageDigest") && method.contains(".update(") && method.contains(".digest("), method);
             assertTrue(method.contains(".close()") && method.contains("addSuppressed"), method);
             assertTrue(method.contains(".renameTo("), method);
             assertTrue(method.contains("TransferProtocol.writeFrame") && method.contains("TransferProtocol.transferId"), method);
             assertTrue(method.contains(".delete()"), method);
             assertTrue(candidate.diagnostics().stream().anyMatch(diagnostic ->
                             diagnostic.message().contains("single-resource cleanup-finalizer")),
                     () -> "Expected accepted receiveOffer cleanup-finalizer shaping diagnostic: "
                             + candidate.diagnostics());
         }

         @Test
         void realFileTransferAggressiveHandleConnectionImprovesValidatedQuality() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");

             DexConversionIr deterministicConversion = new DexConversionIr();
             deterministicConversion.setJvmLoweringPolicy(JvmLoweringPolicy.DETERMINISTIC_LOCAL);
             ConversionResult deterministic = deterministicConversion.toClasses(dex);
             assertTrue(deterministic.errors().isEmpty(), deterministic.errors()::toString);

             DexConversionIr aggressiveConversion = new DexConversionIr();
             aggressiveConversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
             ConversionResult aggressive = aggressiveConversion.toClasses(loadSampleDex("REAL-FileTransfer", "classes5.dex"));
             assertTrue(aggressive.errors().isEmpty(), aggressive.errors()::toString);

             String deterministicSource = Decompile.decompile(owner, deterministic.classes().get(owner));
             String aggressiveSource = Decompile.decompile(owner, aggressive.classes().get(owner));
             String deterministicBytecode = Decompile.bytecode(deterministic.classes().get(owner));
             String aggressiveBytecode = Decompile.bytecode(aggressive.classes().get(owner));
             DecompilationQualityReport.MethodMetrics baseline = DecompilationQualityReport.capture(
                     owner, "handleConnection", deterministic.classes().get(owner), deterministicSource,
                     deterministic.diagnostics().values().stream().flatMap(List::stream).toList());
             DecompilationQualityReport.MethodMetrics candidate = DecompilationQualityReport.capture(
                     owner, "handleConnection", aggressive.classes().get(owner), aggressiveSource,
                     aggressive.diagnostics().values().stream().flatMap(List::stream).toList());
             assertFalse(candidate.failureMarkers().contains("Decompilation failed"), candidate::summary);
             assertFalse(candidate.failureMarkers().contains("Exception decompiling"), candidate::summary);
             assertEquals(0, candidate.syntheticBlockCount(),
                     () -> "Aggressive output retained synthetic block scaffolding: " + candidate.summary());
             assertTrue(candidate.aliasCount() < baseline.aliasCount()
                             || candidate.storeCount() < baseline.storeCount(),
                     () -> "Expected validated fallback quality improvement.\n"
                     + baseline.summary() + "\n" + candidate.summary());
             assertTrue(candidate.syntheticLabelCount() < baseline.syntheticLabelCount(),
                     () -> "Expected cleanup-tail label reduction.\n"
                     + baseline.summary() + "\n" + candidate.summary());
             assertTrue(candidate.syntheticLabelCount() <= baseline.syntheticLabelCount() - 10,
                     () -> "Expected direct exceptional-entry shaping to remove routing labels.\n"
                     + baseline.summary() + "\n" + candidate.summary());
             assertTrue(candidate.handlerCount() < baseline.handlerCount(),
                     () -> "Expected equivalent aggressive catch routes to coalesce.\n"
                     + baseline.summary() + "\n" + candidate.summary());
             assertTrue(candidate.diagnostics().stream().anyMatch(diagnostic ->
                             diagnostic.message().contains("direct handler entry")),
                     () -> "Expected an accepted direct handler-entry proof: " + candidate.diagnostics());
             assertTrue(countAdjacentBytecodeOps(deterministicBytecode,
                             "INVOKESTATIC java/security/MessageDigest.getInstance", "ASTORE")
                             > countAdjacentBytecodeOps(aggressiveBytecode,
                             "INVOKESTATIC java/security/MessageDigest.getInstance", "ASTORE"),
                     () -> "Expected aggressive lowering to retain fewer digest receivers in locals");
             assertFalse(candidate.aggressiveFallback(), () -> "The improved aggressive layout unexpectedly fell back: "
                     + candidate.diagnostics());
             assertFalse(candidate.diagnostics().stream().anyMatch(diagnostic ->
                             diagnostic.message().contains("retried guarded lowering")
                                     || diagnostic.message().contains("retried deterministic lowering")),
                     () -> "Unexpected aggressive fallback diagnostic: " + candidate.diagnostics());
             String aggressiveHandleSource = DecompilationQualityReport.extractMethod(aggressiveSource, "handleConnection");
             String deterministicHandleSource = DecompilationQualityReport.extractMethod(deterministicSource, "handleConnection");
             assertFalse(aggressiveHandleSource.matches("(?s).*if\\s*\\([^)]*==\\s*null\\)\\s*\\{\\s*\\}\\s*.*containsKey.*"),
                     () -> "Aggressive cleanup retained an empty nullable guard: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.contains("if (string != null) {"),
                     () -> "Expected nullable cleanup to decompile as a non-null short-circuit region: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.contains("if (!this.cancelledTransfers.containsKey(string))"),
                     () -> "Expected nullable cleanup to retain the cancellation-map test: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.matches("(?s).*DataInputStream\\s+\\w+\\s*=\\s*new DataInputStream\\s*\\(\\s*(?:socket|socket2|resource)\\.getInputStream\\(\\)\\s*\\).*"),
                     () -> "Expected canonical DataInputStream resource acquisition: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.matches("(?s).*DataOutputStream\\s+\\w+\\s*=\\s*new DataOutputStream\\s*\\(\\s*(?:socket|socket2|resource)\\.getOutputStream\\(\\)\\s*\\).*"),
                     () -> "Expected canonical DataOutputStream resource acquisition: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.contains("DataInputStream in = new DataInputStream"),
                     () -> "Expected a stable debug identity for the input resource: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.contains("DataOutputStream out = new DataOutputStream"),
                     () -> "Expected a stable debug identity for the output resource: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.matches("(?s).*Socket\\s+(?:socket2|resource|connection)\\s*=\\s*socket\\s*;.*"),
                     () -> "Expected the protected socket resource to retain a distinct resource identity: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.matches("(?s).*transferRequest\\s*==\\s*null\\s*\\?\\s*null\\s*:\\s*transferRequest\\.getId\\(\\).*"),
                     () -> "Expected direct null-conditional transfer-id derivation: " + aggressiveHandleSource);
             assertFalse(aggressiveHandleSource.matches("(?s).*\\(\\w+\\s*=\\s*transferRequest\\.getId\\(\\)\\).*"),
                     () -> "Aggressive output retained a relay local around request.getId(): " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.contains("addSuppressed"),
                     () -> "Aggressive nested-resource output lost close-failure suppression: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.matches("(?s).*activeSockets\\.remove\\(string\\);\\s*this\\.cancelledTransfers\\.remove\\(string\\);\\s*return;\\s*}\\s*catch \\(Throwable.*"),
                     () -> "Expected the complete normal cleanup tail before the failure handler: " + aggressiveHandleSource);
             assertTrue(candidate.diagnostics().stream().anyMatch(diagnostic ->
                             diagnostic.message().contains("outer cleanup range extension")),
                     () -> "Expected paired outer cleanup range shaping: " + candidate.diagnostics());
             assertFalse(aggressiveHandleSource.contains("InputStream inputStream = socket.getInputStream()"),
                     () -> "Aggressive output retained the raw input acquisition temporary: " + aggressiveHandleSource);
             assertFalse(aggressiveHandleSource.contains("OutputStream outputStream = socket.getOutputStream()"),
                     () -> "Aggressive output retained the raw output acquisition temporary: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.contains("publishProgress(new TransferProgress("),
                     () -> "Aggressive output retained the one-use failure-progress constructor temporary: "
                             + aggressiveHandleSource);
             assertFalse(aggressiveHandleSource.matches("(?s).*TransferProgress\\s+\\w+\\s*=\\s*new TransferProgress\\s*\\(.*"),
                     () -> "Aggressive output retained a standalone failure-progress local: " + aggressiveHandleSource);
             // Mixed-width constructor arguments remain local-materialized
             // until a stack-aware proof can fuse them.  The important
             // quality invariant here is that those locals are passed in the
             // declared descriptor order, rather than being shifted into
             // unrelated enum/string/long casts.
             assertFalse(aggressiveHandleSource.contains("(String)TransferProgress.Direction"),
                     () -> "Aggressive output has shifted TransferProgress constructor categories: "
                             + aggressiveHandleSource);
             assertFalse(aggressiveHandleSource.contains("(Direction)transferRequest.getName()"),
                     () -> "Aggressive output assigned the request name to the direction argument: "
                             + aggressiveHandleSource);
             assertFalse(aggressiveHandleSource.contains("(State)string"),
                     () -> "Aggressive output assigned the transfer id to the state argument: "
                             + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.contains("new TransferProgress("),
                     () -> "Expected direct failure-progress construction: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.matches("(?s).*generateCertificate\\s*\\(\\s*new ByteArrayInputStream\\s*\\(\\s*hello\\.certificate\\s*\\)\\s*\\).*"),
                     () -> "Aggressive output retained the one-use certificate input temporary: " + aggressiveHandleSource);
             assertFalse(aggressiveHandleSource.contains("ByteArrayInputStream byteArrayInputStream"),
                     () -> "Aggressive output retained a standalone certificate input local: " + aggressiveHandleSource);
             assertFalse(aggressiveHandleSource.matches("(?s).*StringBuilder\\s+\\w+\\s*=.*Peer identity mismatch.*"),
                     () -> "Aggressive output retained the one-use mismatch builder local: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.matches("(?s).*Log\\.w[^;]*Peer identity mismatch id=.*calculatedFingerprint=.*"),
                     () -> "Expected the mismatch builder chain to be emitted at its Log.w consumer: " + aggressiveHandleSource);
             assertTrue(aggressiveHandleSource.matches("(?s).*hello\\s*\\([^;]*this\\.identity\\.deviceId\\(\\).*"),
                     () -> "Expected the one-use device-id producer to be fused into hello: " + aggressiveHandleSource);
             assertFalse(aggressiveHandleSource.matches("(?s).*String\\s+\\w+\\s*=\\s*this\\.identity\\.deviceId\\(\\).*"),
                     () -> "Aggressive output retained a one-use device-id local: " + aggressiveHandleSource);
             assertFalse(aggressiveHandleSource.contains("IOException iOException = new IOException"),
                     () -> "Aggressive output retained a constructor-to-throw temporary: " + aggressiveHandleSource);
             assertTrue(countOccurrences(aggressiveHandleSource, "throw new IOException") >= 5,
                     () -> "Expected direct protocol validation throws: " + aggressiveHandleSource);
             assertTrue(candidate.diagnostics().stream().anyMatch(diagnostic ->
                             diagnostic.message().contains("resource-constructor shaping")),
                     () -> "Expected resource constructor shaping diagnostic: " + candidate.diagnostics());
             assertTrue(countOccurrences(aggressiveHandleSource, "while (true)")
                             < countOccurrences(deterministicHandleSource, "while (true)"),
                     () -> "Expected nested resource envelope shaping to reduce synthetic infinite loops\n"
                     + "deterministic=" + countOccurrences(deterministicHandleSource, "while (true)")
                     + " aggressive=" + countOccurrences(aggressiveHandleSource, "while (true)"));
             assertFalse(aggressiveHandleSource.matches("(?s).*\\bvar\\d+_\\d+\\s*=\\s*var\\d+_\\d+\\s*=\\s*this\\.peers\\.get.*"),
                     () -> "Raw peer lookup alias remains in aggressive output: " + aggressiveHandleSource);
             assertTrue(candidate.diagnostics().stream().anyMatch(diagnostic ->
                             diagnostic.message().contains("local materialization cleanup")),
                     () -> "No additional local cleanup was applied: " + candidate.summary());
             assertTrue(candidate.diagnostics().stream().anyMatch(diagnostic ->
                             diagnostic.message().matches(".*[1-9][0-9]* equivalent handler bridge.*")),
                     () -> "No equivalent close-handler bridge was normalized: " + candidate.diagnostics());
             assertTrue(candidate.diagnostics().stream().anyMatch(diagnostic ->
                             diagnostic.message().matches(".*[1-9][0-9]* shared normal cleanup tail.*")),
                     () -> "No shared normal cleanup tail was duplicated: " + candidate.diagnostics());
             assertTrue(candidate.diagnostics().stream().anyMatch(diagnostic ->
                             diagnostic.message().matches(".*[1-9][0-9]* interleaved resource-close handler.*")),
                     () -> "No resource-close handler was interleaved beside its protected close: "
                             + candidate.diagnostics());
             assertTrue(candidate.diagnostics().stream().anyMatch(diagnostic ->
                             diagnostic.message().matches(".*[1-9][0-9]* late expression slice.*")),
                     () -> "No post-layout expression slice was fused: " + candidate.diagnostics());
             assertTrue(aggressiveHandleSource.matches("(?s).*pairings\\.isKnown\\([^;]+\\)\\s*&&\\s*!?\\s*this\\.pairings\\.isPaired\\([^;]+\\).*"),
                     () -> "Expected the pairing validation to decompile as a short-circuit condition: " + aggressiveHandleSource);
         }


         @Test
         void realFileTransferAcceptLoopCoalescesIdenticalHandlerEntries() throws Exception {
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");
             String owner = "com/example/imageserver/transfer/TransferService";
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             ConversionResult conversionResult = conversion.toClasses(dex);
             assertTrue(conversionResult.errors().isEmpty(), conversionResult.errors()::toString);
             byte[] bytecode = conversionResult.classes().get(owner);
             assertNotNull(bytecode);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("private void acceptLoop");
             int end = decompiled.indexOf("\n    }", start);
             assertTrue(start >= 0 && end > start,
                     () -> "Missing TransferService.acceptLoop in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertFalse(method.contains("$ExternalSyntheticLambda1"), method);
             assertTrue(method.contains("execute(() -> this.handleConnection(socket, false, null))"), method);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("Decompilation failed"), method);
             assertTrue(method.split("catch \\(IOException").length <= 5, method);
         }

         @Test
         void realFileTransferAggressiveAcceptLoopCoalescesDeadHandlerState() throws Exception {
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");
             String owner = "com/example/imageserver/transfer/TransferService";
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
             ConversionResult result = conversion.toClasses(dex);
             assertTrue(result.errors().isEmpty(), result.errors()::toString);
             byte[] bytecode = result.classes().get(owner);
             assertNotNull(bytecode);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("private void acceptLoop");
             int end = decompiled.indexOf("\n    }", start);
             assertTrue(start >= 0 && end > start, decompiled);
             String method = decompiled.substring(start, end);
             assertEquals(1, method.split("catch \\(SocketTimeoutException").length - 1, method);
             assertEquals(1, method.split("catch \\(IOException").length - 1, method);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("Decompilation failed"), method);
         }

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

         @Test
         void realFileTransferAwaitPairingUsesStructuredShortCircuit() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             byte[] bytecode = Converters.IR.toJavaClass(cls);
             Decompile.verify(bytecode);
             assertTrue(bytecode.length > 0);
         }

         @Test
         void realFileTransferAggressiveAwaitPairingCoalescesInvariantHandlers() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
              ConversionResult result = conversion.toClasses(loadSampleDex("REAL-FileTransfer", "classes5.dex"));
              assertTrue(result.errors().isEmpty(), result.errors()::toString);
              byte[] bytecode = result.classes().get(owner);
             assertNotNull(bytecode);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("private boolean awaitPairing");
             int end = decompiled.indexOf("\n    }", start);
             assertTrue(start >= 0 && end > start, decompiled);
             String method = decompiled.substring(start, end);
             assertEquals(1, method.split("catch \\(InterruptedException").length - 1, method);
             assertEquals(0, method.split("catch \\(Throwable").length - 1, method);
             assertEquals(1, method.split("finally").length - 1, method);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("Decompilation failed"), method);
         }

         @Test
         void realFileTransferStartNsdKeepsConcreteReferenceLocals() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
             ConversionResult result = conversion.toClasses(loadSampleDex("REAL-FileTransfer", "classes5.dex"));
             assertTrue(result.errors().isEmpty(), result.errors()::toString);
             byte[] bytecode = result.classes().get(owner);
             assertNotNull(bytecode);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("void startNsd()");
             int end = decompiled.indexOf("\n    }", start);
             assertTrue(start >= 0 && end > start, decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("registeredService = new NsdServiceInfo()"), method);
             assertTrue(method.contains("setServiceName") && method.contains("setServiceType")
                     && method.contains("setPort") && method.contains("setAttribute"), method);
             int setupEnd = method.indexOf("this.registrationListener");
             String setup = setupEnd < 0 ? method : method.substring(0, setupEnd);
             assertFalse(setup.contains("NsdServiceInfo nsdServiceInfo")
                     || setup.contains("MulticastLock multicastLock")
                     || setup.contains("RegistrationListener registrationListener")
                     || setup.contains("DiscoveryListener discoveryListener")
                     || setup.contains("StringBuilder stringBuilder")
                     || setup.contains("String string = this.deviceId"), setup);
         }

         @Test
        void realFileTransferGetDestinationTreeKeepsConcreteUriAtJoin() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             byte[] bytecode = Converters.IR.toJavaClass(cls);
             Decompile.verify(bytecode);
             assertTrue(bytecode.length > 0);
         }

         @Test
         void realFileTransferGetDestinationTreeKeepsDirectNullableReturns() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("Uri getDestinationTree");
             int end = decompiled.indexOf("\n    }\n", start);
             if (end < 0) end = decompiled.length();
             assertTrue(start >= 0 && end > start,
                     () -> "Missing getDestinationTree in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("return null;"), method);
             assertTrue(method.contains("Uri.parse"), method);
             assertFalse(method.contains("Uri uri = null"), method);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("Decompilation failed"), method);
         }

         @Test
         void realFileTransferIsAvailableRemainsDirectFieldReturn() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("boolean isAvailable()");
             int end = decompiled.indexOf("\n    }\n", start);
             if (end < 0) end = decompiled.length();
             assertTrue(start >= 0 && end > start,
                     () -> "Missing isAvailable in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("return this.available;"), method);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("Decompilation failed"), method);
         }

         @Test
         void realFileTransferSameServiceTypeKeepsGuardedBooleanChain() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("boolean sameServiceType(");
             int end = decompiled.indexOf("\n    }\n", start);
             if (end < 0) end = decompiled.length();
             assertTrue(start >= 0 && end > start,
                     () -> "Missing sameServiceType in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("SERVICE_TYPE"), method);
             assertTrue(method.contains("_imageserver._tcp."), method);
             assertTrue(method.contains("equals"), method);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("Decompilation failed"), method);
         }

         @Test
         void realFileTransferEnsureIdentityDecompilesWithoutFailureStub() throws Exception {
             String owner = "com/example/imageserver/transfer/IdentityStore";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             byte[] bytecode = Converters.IR.toJavaClass(cls);
             Decompile.verify(bytecode);
             assertTrue(bytecode.length > 0);
         }

         @Test
         void realFileTransferEnsureIdentityPreservesSynchronizedMethodFlag() throws Exception {
             String owner = "com/example/imageserver/transfer/IdentityStore";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             byte[] bytecode = Converters.IR.toJavaClass(cls);
             AtomicBoolean found = new AtomicBoolean();
             new ClassReader(bytecode).accept(new ClassVisitor(ASM9) {
                 @Override
                 public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                   String signature, String[] exceptions) {
                     if ("ensureIdentity".equals(name)) {
                         found.set(true);
                         assertTrue((access & ACC_SYNCHRONIZED) != 0,
                                 "ensureIdentity lost ACC_SYNCHRONIZED");
                     }
                     return null;
                 }
             }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
             assertTrue(found.get(), "Converted IdentityStore lacks ensureIdentity");
         }

         @Test
         void realFileTransferHandleConnectionDecompilesWithoutFailureStub() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             byte[] bytecode = Converters.IR.toJavaClass(cls);
             String decompiled = Decompile.decompile(owner, bytecode);
             assertNotNull(decompiled, () -> "CFR produced no decompiled output:\n"
                     + Decompile.bytecode(bytecode));
             int start = decompiled.indexOf("private void handleConnection");
             int end = decompiled.indexOf("static /* synthetic */ void lambda$notifyPeersTo$5", start + 1);
             assertTrue(start >= 0 && end > start,
                     () -> "Missing handleConnection in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertFalse(method.contains("Decompilation failed"),
                     () -> "CFR emitted a decompilation failure stub for handleConnection:\n"
                             + method + "\n\nBytecode:\n" + Decompile.bytecode(bytecode));
         }

         @Test
         void realFileTransferHandleConnectionPreservesNestedResourceAndFailureMarkersAcrossPolicies() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");
             for (JvmLoweringPolicy policy : JvmLoweringPolicy.values()) {
                 DexConversionIr conversion = new DexConversionIr();
                 conversion.setJvmLoweringPolicy(policy);
                 ConversionResult result = conversion.toClasses(dex);
                 assertTrue(result.errors().isEmpty(), () -> policy + " errors: " + result.errors());
                 byte[] bytecode = result.classes().get(owner);
                 assertNotNull(bytecode, policy.name());
                 Decompile.verify(bytecode);

                 AtomicInteger tryCatchCount = new AtomicInteger();
                 new ClassReader(bytecode).accept(new ClassVisitor(ASM9) {
                     @Override
                     public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                         if (!"handleConnection".equals(name)) return null;
                         return new MethodVisitor(ASM9) {
                             @Override
                             public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                                             org.objectweb.asm.Label end,
                                                             org.objectweb.asm.Label handler,
                                                             String type) {
                                 tryCatchCount.incrementAndGet();
                             }
                         };
                     }
                 }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                 assertTrue(tryCatchCount.get() > 0, () -> policy + " lost handleConnection protected ranges");

                 String decompiled = Decompile.decompile(owner, bytecode);
                 int start = decompiled.indexOf("private void handleConnection");
                 int end = decompiled.indexOf("static /* synthetic */ void lambda$notifyPeersTo$5", start + 1);
                 assertTrue(start >= 0 && end > start, () -> policy + " missing handleConnection:\n" + decompiled);
                 String method = decompiled.substring(start, end);
                 assertTrue(method.contains("DataInputStream") && method.contains("DataOutputStream"),
                         () -> policy + " lost nested stream resources:\n" + method);
                 assertTrue(method.contains("TransferProtocol") && method.contains("activeSockets")
                                 && method.contains("cancelledTransfers"),
                         () -> policy + " lost handshake/finally markers:\n" + method);
                 assertFalse(method.contains("Decompilation failed")
                                 || method.contains("Unable to fully structure code")
                                 || method.contains("Exception decompiling"),
                         () -> policy + " emitted a CFR failure marker:\n" + method);
             }
         }

         @Test
         void realFileTransferSameServiceTypeInlinesReturnCondition() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             byte[] bytecode = Converters.IR.toJavaClass(cls);
             Decompile.verify(bytecode);
             assertTrue(bytecode.length > 0);
         }

         @Test
         void realFileTransferBuildNotificationKeepsFluentBuilderChain() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("private Notification buildNotification");
             int end = decompiled.indexOf("private void createNotificationChannel", start + 1);
             assertTrue(start >= 0 && end > start,
                     () -> "Missing buildNotification in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("NotificationCompat.Builder"), method);
             assertTrue(method.contains("setSmallIcon"), method);
             assertTrue(method.contains("addAction"), method);
             assertTrue(method.contains(".build()"), method);
         }

         @Test
         void realFileTransferPairingStoreIsKnownInlinesDeadConcatSetup() throws Exception {
             String owner = "com/example/imageserver/transfer/PairingStore";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public boolean isKnown");
             int end = decompiled.indexOf("public boolean isPaired", start + 1);
             assertTrue(start >= 0 && end > start,
                     () -> "Missing PairingStore.isKnown in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("return this.preferences.contains(\"fingerprint_\" + string);"), method);
             assertFalse(method.contains("StringBuilder"), method);
             assertFalse(method.contains("SharedPreferences sharedPreferences"), method);
         }

         @Test
         void realFileTransferOpenDoesNotDuplicateThrownException() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferFiles";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public static InputStream open");
             int end = decompiled.indexOf("public static ", start + 1);
             assertTrue(start >= 0 && end > start,
                     () -> "Missing TransferFiles.open in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("throw new IllegalStateException"), method);
             assertFalse(method.contains("IllegalStateException illegalStateException"), method);
         }

         @Test
         void realFileTransferSanitizeNameReducesReturnAlias() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferFiles";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public static String sanitizeName");
             int end = decompiled.indexOf("public static ", start + 1);
             assertTrue(start >= 0 && end > start,
                     () -> "Missing sanitizeName in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("return string2;"), method);
             assertTrue(method.contains("return string2.substring"), method);
             assertFalse(method.contains("String string4 = string2"), method);
         }

         @Test
         void realFileTransferHexRecoversEnhancedArrayLoop() throws Exception {
             String owner = "com/example/imageserver/transfer/IdentityStore";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public static String hex");
             int end = decompiled.indexOf("public static ", start + 1);
             if (end < 0) end = decompiled.length();
             assertTrue(start >= 0 && end > start,
                     () -> "Missing hex in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("for (byte"), method);
             assertTrue(method.contains(": byArray"), method);
             assertTrue(method.contains("new StringBuilder(byArray.length * 2)"), method);
             assertTrue(method.contains("String.format"), method);
             assertFalse(method.contains("Unable to fully structure code"), method);
         }

         @Test
         void realFileTransferCompareBytesRecoversBoundedLoop() throws Exception {
             String owner = "com/example/imageserver/transfer/IdentityStore";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("private static int compareBytes");
             int end = decompiled.indexOf("public static String hex", start + 1);
             assertTrue(start >= 0 && end > start,
                     () -> "Missing compareBytes in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("for (int i = 0; i < Math.min"), method);
             assertTrue(method.contains("Byte.compare"), method);
             assertTrue(method.contains("return Integer.compare"), method);
             assertFalse(method.contains("Unable to fully structure code"), method);
         }

         @Test
         void realFileTransferAggressiveCompareBytesRemainsStructured() throws Exception {
             String owner = "com/example/imageserver/transfer/IdentityStore";
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
             ConversionResult result = conversion.toClasses(dex);
             byte[] bytecode = result.classes().get(owner);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("private static int compareBytes");
             int end = decompiled.indexOf("public static String hex", start + 1);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("for (int i = 0; i < Math.min")
                             && method.contains("Byte.compare")
                             && method.contains("return Integer.compare"), method);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("** GOTO")
                             || method.contains("Exception decompiling")
                             || method.contains("This method has failed to decompile")
                             || method.contains("Decompilation failed"), method);
         }

         @Test
         void realFileTransferUniqueNameRecoversCountedLoop() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferFiles";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public static String uniqueName");
             int end = decompiled.indexOf("public static ", start + 1);
             if (end < 0) end = decompiled.length();
             assertTrue(start >= 0 && end > start,
                     () -> "Missing uniqueName in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("for (int i = 1; i < 10000; ++i)"), method);
             assertTrue(method.contains("String.format"), method);
             assertTrue(method.contains("System.currentTimeMillis"), method);
             assertFalse(method.contains("Unable to fully structure code"), method);
         }

         @Test
         void realFileTransferConfirmPairingRemainsStructured() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("confirmPairing(");
             int end = decompiled.indexOf("\n    }\n", start);
             if (end < 0) end = decompiled.length();
             assertTrue(start >= 0 && end > start,
                     () -> "Missing confirmPairing in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("pendingPairings"), method);
             assertTrue(method.contains("countDown"), method);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("Decompilation failed"), method);
         }

         @Test
         void realFileTransferCancelTransferRemainsStructured() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("cancelTransfer(");
             int end = decompiled.indexOf("\n    }\n", start);
             if (end < 0) end = decompiled.length();
             assertTrue(start >= 0 && end > start,
                     () -> "Missing cancelTransfer in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("cancelledTransfers"), method);
             assertTrue(method.contains("activeSockets.remove"), method);
             assertTrue(method.contains("socket.close"), method);
             assertTrue(method.contains("CANCELLED"), method);
             assertTrue(method.contains("publishProgress"), method);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("Decompilation failed"), method);
         }

         @Test
         void realFileTransferMimeTypeKeepsNullableDirectReturn() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferFiles";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public static String mimeType");
             int end = decompiled.indexOf("public static ", start + 1);
             if (end < 0) end = decompiled.length();
             assertTrue(start >= 0 && end > start,
                     () -> "Missing mimeType in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertTrue(method.contains("application/octet-stream"), method);
             assertTrue(method.contains("getType"), method);
             assertFalse(method.contains("String string2 = string"), method);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("Decompilation failed"), method);
         }

         @Test
         void realFileTransferDisplayNameStructuresTryCatch() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferFiles";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             byte[] bytecode = Converters.IR.toJavaClass(cls);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public static String displayName");
             int end = decompiled.indexOf("public static ", start + 1);
             assertTrue(start >= 0 && end > start, () -> "Missing displayName in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             assertFalse(method.contains("Unable to fully structure code"),
                     () -> "displayName retained unstructured exception control flow:\n" + method);
         }

         @Test
         void realFileTransferAggressiveDisplayNameRemainsStructured() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferFiles";
             DexFile dex = loadSampleDex("REAL-FileTransfer", "classes5.dex");
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
             ConversionResult result = conversion.toClasses(dex);
             byte[] bytecode = result.classes().get(owner);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public static String displayName");
             int end = decompiled.indexOf("public static ", start + 1);
             String method = decompiled.substring(start, end);
             assertFalse(method.contains("Unable to fully structure code")
                             || method.contains("** GOTO")
                             || method.contains("Exception decompiling")
                             || method.contains("This method has failed to decompile")
                             || method.contains("Decompilation failed"), method);
         }

         @Test
         void realFileTransferSha256StructuresTryWithResources() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferFiles";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             byte[] bytecode = Converters.IR.toJavaClass(cls);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public static String sha256");
             int end = decompiled.indexOf("public static ", start + 1);
             assertTrue(start >= 0 && end > start, () -> "Missing sha256 in decompiled output:\n" + decompiled);
             String method = decompiled.substring(start, end);
             int trailingComment = method.indexOf("\n    /*");
             if (trailingComment >= 0) method = method.substring(0, trailingComment);
             String sha256Method = method;
             assertFalse(method.contains("Unable to fully structure code") || method.contains("** GOTO")
                             || method.contains("This method has failed to decompile")
                             || method.contains("Exception decompiling")
                             || method.contains("Decompilation failed")
                             || method.contains("block5:"),
                     () -> "sha256 retained unstructured try-with-resources control flow:\n" + sha256Method
                             + "\nBYTECODE:\n" + Decompile.bytecode(bytecode));
             assertFalse(method.contains("if (inputStream == null) return"),
                     () -> "sha256 retained an impossible null branch after the read loop:\n" + sha256Method);
         }

         @Test
         void realFileTransferSizeUsesOneLongAccumulatorInGuardedMode() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferFiles";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String decompiled = Decompile.decompile(owner, bytecode);
             int start = decompiled.indexOf("public static long size");
             int end = decompiled.indexOf("public static ", start + 1);
             assertTrue(start >= 0 && end > start, decompiled);
             String method = decompiled.substring(start, end);
             assertFalse(method.contains("long l3 = n"), method);
             assertTrue(method.contains("l += (long)n"), method);
             String trace = Decompile.bytecode(bytecode);
             int bytecodeStart = trace.indexOf("public static size(");
             int bytecodeEnd = trace.indexOf("// access flags", bytecodeStart + 1);
             assertTrue(bytecodeStart >= 0 && bytecodeEnd > bytecodeStart, trace);
             String sizeBytecode = trace.substring(bytecodeStart, bytecodeEnd);
             assertTrue(sizeBytecode.contains("TRYCATCHBLOCK L1 L3"),
                     () -> "Cursor protected range did not begin before the null/resource check:\n"
                             + sizeBytecode);
         }

         @Test
         void realFileTransferSendOfferClosesInputStreamOnNormalPath() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String trace = Decompile.bytecode(bytecode);
             int start = trace.indexOf("\n  private sendOffer");
             int end = trace.indexOf("\n  // access flags", start + 1);
             if (end < 0) end = trace.length();
             assertTrue(start >= 0 && end > start, trace);
             String method = trace.substring(start, end);
             int nullCheck = method.indexOf("IFNULL");
             int close = method.indexOf("INVOKEVIRTUAL java/io/InputStream.close ()V");
             assertTrue(nullCheck >= 0 && close > nullCheck, method);
         }

         @Test
         void realFileTransferSendOfferFusesDataIntoWriteFrame() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String trace = Decompile.bytecode(bytecode);
             int start = trace.indexOf("\n  private sendOffer");
             int end = trace.indexOf("\n  // access flags", start + 1);
             if (end < 0) end = trace.length();
             assertTrue(start >= 0 && end > start, trace);
             String method = trace.substring(start, end);
             String data = "INVOKESTATIC com/example/imageserver/transfer/TransferProtocol.data";
             String write = "INVOKESTATIC com/example/imageserver/transfer/TransferProtocol.writeFrame";
             int dataIndex = method.indexOf(data);
             int writeIndex = method.indexOf(write, dataIndex + data.length());
             assertTrue(dataIndex >= 0 && writeIndex > dataIndex, method);
             assertFalse(method.substring(dataIndex + data.length(), writeIndex).contains("ASTORE"), method);
         }

         @Test
         void realFileTransferReceiveOfferRetainsWriteVerifyAndRenamePath() throws Exception {
             String owner = "com/example/imageserver/transfer/TransferService";
             ClassDefinition cls = loadSampleClass("REAL-FileTransfer", "classes5.dex", owner);
             DexConversionIr conversion = new DexConversionIr();
             conversion.setJvmLoweringPolicy(JvmLoweringPolicy.GUARDED_OPTIMIZED);
             byte[] bytecode = conversion.toJavaClass(cls);
             Decompile.verify(bytecode);
             String trace = Decompile.bytecode(bytecode);
             int start = trace.indexOf("\n  private receiveOffer");
             int end = trace.indexOf("\n  // access flags", start + 1);
             if (end < 0) end = trace.length();
             assertTrue(start >= 0 && end > start, trace);
             String method = trace.substring(start, end);
             assertTrue(method.contains("TransferProtocol.readData"), method);
             assertTrue(method.contains("java/io/OutputStream.write"), method);
             assertTrue(method.contains("java/io/OutputStream.close"), method);
             assertTrue(method.contains("java/security/MessageDigest.update"), method);
             assertTrue(method.contains("DocumentFile.renameTo"), method);
         }

     }

    private static MethodMember method(String name, MethodType type, Code code, int access) {
        MethodMember method = new MethodMember(name, type, access);
        method.setCode(code);
        return method;
    }

    private static ClassDefinition loadSampleClass(String sample, String owner) throws Exception {
        return loadSampleClass(sample, "classes.dex", owner);
    }

    private static DexFile loadSampleDex(String sample, String dexName) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path path = cwd.resolve("test-data").resolve("samples").resolve(sample).resolve(dexName);
        if (!Files.exists(path)) {
            path = cwd.resolve("..").resolve("test-data").resolve("samples").resolve(sample).resolve(dexName).normalize();
        }
        Input dexInput = Input.wrap(Files.readAllBytes(path));
        DexHeader header = DexHeader.CODEC.read(dexInput);
        return DexFile.CODEC.map(header, header.map());
    }

    private static ClassDefinition loadSampleClass(String sample, String dexName, String owner) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path path = cwd.resolve("test-data").resolve("samples").resolve(sample).resolve(dexName);
        if (!Files.exists(path)) {
            path = cwd.resolve("..").resolve("test-data").resolve("samples").resolve(sample).resolve(dexName).normalize();
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
        Decompile.verify(bytecode);
        assertTrue(bytecode.length > 0, "Conversion produced no classfile bytes");
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
    private static int countAdjacentBytecodeOps(String bytecode, String producer, String consumerPrefix) {
        String[] lines = bytecode.split("\\R");
        int count = 0;
        for (int index = 0; index + 1 < lines.length; index++) {
            if (!lines[index].contains(producer)) continue;
            for (int next = index + 1; next < lines.length; next++) {
                String line = lines[next].trim();
                if (line.isEmpty()) continue;
                if (line.startsWith(consumerPrefix)) count++;
                break;
            }
        }
        return count;
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        for (int index = 0; (index = source.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }

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

    private static Code divisionCatchCode() {
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Code code = code(2, 0,
                start,
                new ConstInstruction(0, 1),
                new ConstInstruction(1, 0),
                new BinaryInstruction(me.darknet.dex.file.instructions.Opcodes.DIV_INT, 0, 0, 1),
                end,
                new ReturnInstruction(0),
                handler,
                new MoveExceptionInstruction(1),
                new ConstInstruction(0, 7),
                new ReturnInstruction(0));
        code.addTryCatch(new TryCatch(start, end,
                List.of(new Handler(handler, Types.instanceType(ArithmeticException.class)))));
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
                offset += instruction.unitSize();
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
