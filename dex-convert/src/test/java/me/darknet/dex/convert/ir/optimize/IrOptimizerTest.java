package me.darknet.dex.convert.ir.optimize;

import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.build.IrBuilder;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.util.Decompile;
import me.darknet.dex.convert.util.IrTestUtils;
import me.darknet.dex.file.instructions.Opcodes;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.BinaryInstruction;
import me.darknet.dex.tree.definitions.instructions.BranchInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.GotoInstruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.tree.definitions.instructions.NewInstanceInstruction;
import me.darknet.dex.tree.definitions.instructions.Return;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Baseline optimization tests for {@link BaseIrOptimizer}
 */
class IrOptimizerTest {

	// TODO: Potential optimizations not yet implemented
	//  - Peephole optimizations for redundant loads/stores
	//  - Value range propagation
	//  - Algebraic simplifications
	//     - x * 1 -> x
	//     - x + 0 -> x
	//     - x - 0 -> x
	//     - x * 0 -> 0
	//     - x / 1 -> x
	//     - x / x -> 1 (with special handling for zero)
	//     - x - x -> 0
	//     - x << 0 -> x
	//     - x >> 0 -> x
	//     - x & 0 -> 0
	//     - x | 0 -> x
	//     - x ^ 0 -> x
	//  - Inline small methods

	@Test
	void constantFolding() {
		// Pseudocode:
		// int add() {
		//   int a = 2;
		//   int b = 3;
		//   int c = a + b;
		//   return c;
		// }
		MethodMember method = new MethodMember("add", Types.methodTypeFromDescriptor("()I"),
				org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC);
		method.setCode(IrTestUtils.code(3, 0,
				new ConstInstruction(0, 2),
				new ConstInstruction(1, 3),
				new BinaryInstruction(Opcodes.ADD_INT, 2, 0, 1),
				new ReturnInstruction(2)
		));

		IrMethod ir = new IrBuilder(method).build();
		IrMethod irOpt = new IrBuilder(method).build();

		// Optimizer should fold constants
		new BaseIrOptimizer().optimizeMethod(singleMethodContext(irOpt), irOpt);
		if (irOpt.blocks().getFirst().statements().getFirst() instanceof IrOp op && op.canonical() instanceof IrConstant constant) {
			assertEquals(5, constant.constantValue(), "Expected constant folding of 2 + 3 to 5");
		} else {
			fail("Expected first statement to be a folded constant operation");
		}

		// Original should remain unchanged with no optimization
		if (ir.blocks().getFirst().statements().getFirst() instanceof IrOp op) {
			assertSame(op, op.canonical(), "Expected no folding with IrNoopOptimizer");
		} else {
			fail("Expected first statement to still be an operation with no optimization");
		}

		String irOptDecomp = Decompile.decompile("TestClass", irOpt);
		String irDecomp = Decompile.decompile("TestClass", ir);
		assertNotEquals(irOptDecomp, irDecomp, "Expected different decompiled output for optimized vs noop IR");
	}

	@Test
	void deadVariableElimination() {
		// Pseudocode:
		// int dead() {
		//   int x = 1 + 2; // dead code, result is never used
		//   return 0;
		// }
		MethodMember method = new MethodMember("dead", Types.methodTypeFromDescriptor("()I"),
				org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC);
		method.setCode(IrTestUtils.code(4, 0,
				new ConstInstruction(0, 1),
				new ConstInstruction(1, 2),
				new BinaryInstruction(Opcodes.ADD_INT, 2, 0, 1), // dead code
				new ConstInstruction(3, 0),
				new ReturnInstruction(3)
		));

		// Optimizer should remove dead code
		IrMethod ir = new IrBuilder(method).build();
		IrMethod irOpt = new IrBuilder(method).build();
		new BaseIrOptimizer().optimizeMethod(singleMethodContext(irOpt), irOpt);

		String irOptDecomp = Decompile.decompile("TestClass", irOpt);
		String irDecomp = Decompile.decompile("TestClass", ir);
		assertFalse(irOptDecomp.contains("1 + 2"), "Expected dead code to be eliminated in optimized IR");
		assertTrue(irDecomp.contains("1 + 2"), "Expected dead code to be kept in unoptimized IR");
	}

	@Test
	void deadBlockElimination() {
		// Pseudocode:
		// int dead() {
		//   int x = 1;
		//   int y = 2;
		//   if (x == y) {
		//     return 400; // dead block, never reachable
		//   }
		//   return 200;
		// }
		Label deadLabel = new Label();
		MethodMember method = new MethodMember("dead", Types.methodTypeFromDescriptor("()I"),
				org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC);
		method.setCode(IrTestUtils.code(4, 0,
				new ConstInstruction(0, 1),
				new ConstInstruction(1, 2),
				new BranchInstruction(BranchInstruction.TEST_IF_EQ, 0, 1, deadLabel),
				new ConstInstruction(3, 200),
				new ReturnInstruction(3),
				deadLabel,
				new ConstInstruction(3, 400),
				new ReturnInstruction(3)
		));

		// Optimizer should remove dead code
		IrMethod ir = new IrBuilder(method).build();
		IrMethod irOpt = new IrBuilder(method).build();
		new BaseIrOptimizer().optimizeMethod(singleMethodContext(irOpt), irOpt);

		// Disassemble both outputs and check that the optimized version does not contain the dead block, while the original does
		String irOptDecomp = Decompile.bytecode("TestClass", irOpt);
		String irDecomp = Decompile.bytecode("TestClass", ir);
		assertTrue(irDecomp.contains("400"), "Expected dead code to be kept in unoptimized IR");
		assertFalse(irOptDecomp.contains("400"), "Expected dead code to be eliminated in optimized IR");
	}

	@Test
	void noSimplificationForParameterValueBranch() {
		// Pseudocode:
		// int branch(int x) {
		//   return x >= 0 ? 2 : 1;
		// }
		Label elseLabel = new Label();
		Label endLabel = new Label();
		MethodMember method = new MethodMember("branch", Types.methodTypeFromDescriptor("(I)I"),
				org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC);
		method.setCode(IrTestUtils.code(3, 1,
				new BranchInstruction(BranchInstruction.TEST_IF_GE, 0, 2, elseLabel),
				new ConstInstruction(0, 2),
				new GotoInstruction(endLabel),
				elseLabel,
				new ConstInstruction(0, 1),
				endLabel,
				new ReturnInstruction(0)
		));

		// Optimizer should have no idea on how to optimize this since the input comes from
		// a method parameter, which is a black box for the optimizer.
		IrMethod ir = new IrBuilder(method).build();
		IrMethod irOpt = new IrBuilder(method).build();
		new BaseIrOptimizer().optimizeMethod(singleMethodContext(irOpt), irOpt);

		String irDecomp = Decompile.decompile("TestClass", irOpt);
		String irNoopDecomp = Decompile.decompile("TestClass", ir);
		assertEquals(irDecomp, irNoopDecomp, "");
	}

	@Test
	void restoresNamedNestedClassMetadataFromName() {
		// Just make two classes with the common inner class naming pattern.
		ClassDefinition outer = testClass("test/Outer", org.objectweb.asm.Opcodes.ACC_PUBLIC);
		ClassDefinition inner = testClass("test/Outer$Inner", org.objectweb.asm.Opcodes.ACC_PRIVATE);

		// Optimizer should be able to infer the inner/outer relationship and inner class entry from the name alone,
		// even without any IR evidence of creation.
		IrOptimizationContext context = programContext(List.of(outer, inner));
		new BaseIrOptimizer().optimizeProgram(context);

		// Check that inner class metadata was correctly inferred from the name.
		assertEquals(outer.getType(), inner.getEnclosingClass());
		assertNull(inner.getEnclosingMethod());
		assertEquals(1, inner.getInnerClasses().size());
		InnerClass innerEntry = inner.getInnerClasses().getFirst();
		assertEquals("test/Outer$Inner", innerEntry.innerClassName());
		assertEquals("test/Outer", innerEntry.outerClassName());
		assertEquals("Inner", innerEntry.innerName());
		assertEquals(inner.getAccess(), innerEntry.access());
		assertEquals(1, outer.getInnerClasses().size());
		assertEquals(List.of(inner.getType()), outer.getMemberClasses());
	}

	@Test
	void restoresAnonymousClassButLeavesClinitWithoutEnclosingMethod() {
		// Pseudocode:
		// class Outer {
		//   static {
		//     new Object(){}; // Anonymous class created in static initializer
		//   }
		// }
		ClassDefinition outer = testClass("test/Outer", org.objectweb.asm.Opcodes.ACC_PUBLIC);
		ClassDefinition anonymous = testClass("test/Outer$1", org.objectweb.asm.Opcodes.ACC_FINAL);
		anonymous.putMethod(method("<init>", "()V", org.objectweb.asm.Opcodes.ACC_PUBLIC, IrTestUtils.code(1, 1,
				new ReturnInstruction()
		)));
		outer.putMethod(method("<clinit>", "()V", org.objectweb.asm.Opcodes.ACC_STATIC, constructorReturningVoidCode(anonymous.getType())));

		// Run optimizer to collect inner class creation evidence.
		// Because its defined in the static initializer, which is special, it shouldn't record the outer method context.
		IrOptimizationContext context = programContext(List.of(outer, anonymous));
		new BaseIrOptimizer().optimizeProgram(context);

		// Check that the anonymous class is correctly linked to its outer class, but without enclosing method details.
		assertEquals(outer.getType(), anonymous.getEnclosingClass());
		assertNull(anonymous.getEnclosingMethod(), "<clinit> should not be set as enclosing method for anonymous class created in static initializer");
		assertEquals(1, anonymous.getInnerClasses().size());
		assertEquals(anonymous.getType().internalName(), anonymous.getInnerClasses().getFirst().innerClassName());
		assertNull(anonymous.getInnerClasses().getFirst().innerName());
		assertEquals(1, outer.getInnerClasses().size());
		assertTrue(outer.getMemberClasses().isEmpty(), "Anonymous classes should not be listed as member classes");
	}

	@Test
	void restoresLocalAnonymousCreatorMethodWhenUnique() {
		// Pseudocode:
		// class Outer {
		//   Object create() {
		//     return new Object(){}; // Anonymous class created in a non-static method
		//   }
		// }
		ClassDefinition outer = testClass("test/Outer", org.objectweb.asm.Opcodes.ACC_PUBLIC);
		ClassDefinition local = testClass("test/Outer$1Local", org.objectweb.asm.Opcodes.ACC_FINAL);
		local.putMethod(method("<init>", "()V", org.objectweb.asm.Opcodes.ACC_PUBLIC, IrTestUtils.code(1, 1,
				new ReturnInstruction()
		)));
		outer.putMethod(method("create", "()Ljava/lang/Object;", org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
				constructorReturningObjectCode(local.getType())));

		// Run optimizer to collect inner class creation evidence.
		// It should be able to determine that the anonymous class is created in the 'create' method, and set the enclosing method metadata accordingly.
		IrOptimizationContext context = programContext(List.of(outer, local));
		new BaseIrOptimizer().optimizeProgram(context);

		// Check that the anonymous class is correctly linked to its outer class and enclosing method.
		assertEquals(outer.getType(), local.getEnclosingClass());
		assertEquals(new MemberIdentifier("create", "()Ljava/lang/Object;"), local.getEnclosingMethod());
		assertEquals("Local", local.getInnerClasses().getFirst().innerName());
		assertEquals(1, outer.getInnerClasses().size());
	}

	@Test
	void leavesEnclosingMethodUnsetWhenCreatorsAreAmbiguous() {
		// Pseudocode:
		// class Outer {
		//   static Object createA() { return new Object(){} }
		//   static Object createB() { return new Object(){} }
		// }
		ClassDefinition outer = testClass("test/Outer", org.objectweb.asm.Opcodes.ACC_PUBLIC);
		ClassDefinition anonymous = testClass("test/Outer$1", org.objectweb.asm.Opcodes.ACC_FINAL);
		anonymous.putMethod(method("<init>", "()V", org.objectweb.asm.Opcodes.ACC_PUBLIC, IrTestUtils.code(1, 1,
				new ReturnInstruction()
		)));
		outer.putMethod(method("createA", "()Ljava/lang/Object;", org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
				constructorReturningObjectCode(anonymous.getType())));
		outer.putMethod(method("createB", "()Ljava/lang/Object;", org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
				constructorReturningObjectCode(anonymous.getType())));

		// Run optimizer to collect inner class creation evidence.
		IrOptimizationContext context = programContext(List.of(outer, anonymous));
		new BaseIrOptimizer().optimizeProgram(context);

		// Since the anonymous class is created in two different methods,
		// the optimizer should not be able to determine which one is the correct creator method,
		// so it should leave the enclosing method metadata unset to avoid incorrect assumptions.
		assertEquals(outer.getType(), anonymous.getEnclosingClass());
		assertNull(anonymous.getEnclosingMethod());
		assertEquals(1, outer.getInnerClasses().size());
	}

	@Test
	void preservesExistingMetadata() {
		// Create dex models with manually defined inner class metadata that would normally be inferred by the optimizer.
		ClassDefinition outer = testClass("test/Outer", org.objectweb.asm.Opcodes.ACC_PUBLIC);
		ClassDefinition anonymous = testClass("test/Outer$Existing", org.objectweb.asm.Opcodes.ACC_FINAL);
		MemberIdentifier existingMethod = new MemberIdentifier("existing", "()V");
		anonymous.setEnclosingClass(outer.getType());
		anonymous.setEnclosingMethod(existingMethod);
		anonymous.addInnerClass(new InnerClass("test/Outer$Existing", "test/Outer", "Existing", 77));
		outer.putMethod(method("<clinit>", "()V", org.objectweb.asm.Opcodes.ACC_STATIC, constructorReturningVoidCode(anonymous.getType())));

		// Run optimizer to collect inner class creation evidence.
		IrOptimizationContext context = programContext(List.of(outer, anonymous));
		new BaseIrOptimizer().optimizeProgram(context);

		// Look at anonymous enclosing method data. Should be kept.
		assertEquals(outer.getType(), anonymous.getEnclosingClass());
		assertEquals(existingMethod, anonymous.getEnclosingMethod());
		assertEquals(1, anonymous.getInnerClasses().size());

		// Look at anonymous class entry, all fields should be kept.
		InnerClass inner = anonymous.getInnerClasses().getFirst();
		assertEquals("test/Outer$Existing", inner.innerClassName());
		assertEquals("test/Outer", inner.outerClassName());
		assertEquals("Existing", inner.innerName());
		assertEquals(77, inner.access());
		assertEquals(1, outer.getInnerClasses().size());
	}

	/**
	 * @param method
	 * 		Method to optimize.
	 *
	 * @return Dummy context containing only the given method, for testing single-method optimizations.
	 */
	private static IrOptimizationContext singleMethodContext(IrMethod method) {
		ClassDefinition cls = new ClassDefinition(
				Types.instanceTypeFromInternalName("test/OptimizerContext"),
				Types.instanceType(Object.class),
				org.objectweb.asm.Opcodes.ACC_PUBLIC
		);
		cls.putMethod(method.source());
		return new IrOptimizationContext(IrOptimizationContext.ScopeKind.SINGLE_CLASS, List.of(cls), Map.of(cls, List.of(method)));
	}

	private static @NotNull IrOptimizationContext programContext(@NotNull List<ClassDefinition> classes) {
		Map<ClassDefinition, List<IrMethod>> methodsByClass = new HashMap<>();
		for (ClassDefinition cls : classes) {
			List<IrMethod> methods = new ArrayList<>();
			for (MethodMember method : cls.getMethods().values()) {
				if (method.getCode() == null)
					continue;
				methods.add(new IrBuilder(method).build());
			}
			methodsByClass.put(cls, methods);
		}
		return new IrOptimizationContext(IrOptimizationContext.ScopeKind.WHOLE_DEX, classes, methodsByClass);
	}

	private static @NotNull ClassDefinition testClass(@NotNull String name, int access) {
		return new ClassDefinition(Types.instanceTypeFromInternalName(name), Types.instanceType(Object.class), access);
	}

	private static @NotNull MethodMember method(@NotNull String name, @NotNull String descriptor, int access, @NotNull Code code) {
		MethodMember method = new MethodMember(name, Types.methodTypeFromDescriptor(descriptor), access);
		method.setCode(code);
		return method;
	}

	private static @NotNull Code constructorReturningVoidCode(@NotNull InstanceType type) {
		// new T();
		return IrTestUtils.code(1, 0,
				new NewInstanceInstruction(0, type),
				new InvokeInstruction(Invoke.DIRECT, type, "<init>", Types.methodTypeFromDescriptor("()V"), 0),
				new ReturnInstruction()
		);
	}

	private static @NotNull Code constructorReturningObjectCode(@NotNull InstanceType type) {
		// return new T();
		return IrTestUtils.code(1, 0,
				new NewInstanceInstruction(0, type),
				new InvokeInstruction(Invoke.DIRECT, type, "<init>", Types.methodTypeFromDescriptor("()V"), 0),
				new ReturnInstruction(0, Return.OBJECT)
		);
	}
}
