package me.darknet.dex.convert.ir.analysis;

import me.darknet.dex.convert.ir.IrBlock;
import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrNullability;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrType;
import me.darknet.dex.convert.ir.value.IrTypeKind;
import me.darknet.dex.tree.type.Types;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ReferenceType;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.instructions.BranchZeroInstruction;
import me.darknet.dex.tree.definitions.instructions.Label;
import me.darknet.dex.convert.ir.statement.IrTerminator;
import me.darknet.dex.convert.ir.statement.IrTerminatorKind;
import me.darknet.dex.file.instructions.Opcodes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IrTypeAnalysisTest {
    @Test
    void joinsCategoriesAndReferencesConservatively() {
        assertEquals(IrTypeKind.INT, IrType.join(IrType.from(Types.INT), IrType.from(Types.BOOLEAN)).kind());
        assertEquals(IrTypeKind.TOP, IrType.join(IrType.from(Types.INT), IrType.from(Types.FLOAT)).kind());
        IrType string = IrType.from(Types.instanceType(String.class));
        assertEquals(string, IrType.join(string, string));
		IrType differing = IrType.join(string, IrType.from(Types.instanceType(Integer.class)));
		assertEquals(IrTypeKind.REFERENCE, differing.kind());
		assertEquals(Types.OBJECT, differing.exactReference());
		assertEquals(IrTypeKind.REFERENCE,
				IrType.join(IrType.unknown(Types.OBJECT), string).kind());
		assertEquals(IrTypeKind.UNKNOWN, IrType.unknown(Types.OBJECT).kind());
        assertEquals(IrNullability.MAYBE_NULL,
                IrType.join(string, new IrType(IrTypeKind.REFERENCE, null, IrNullability.NULL)).nullability());
    }

	@Test
	void preservesProvenReferenceSubtypesWhenConstrainedByAnErasedArrayApi() {
		ReferenceType string = Types.instanceType(String.class);
		ReferenceType stringArray = new ArrayType(string);
		ReferenceType objectArray = new ArrayType(Types.OBJECT);
		assertEquals(true, IrTypeHierarchy.isAssignable(string, Types.OBJECT, IrTypeResolver.EMPTY));
		assertEquals(true, IrTypeHierarchy.isAssignable(stringArray, objectArray, IrTypeResolver.EMPTY));
		assertEquals(false, IrTypeHierarchy.isAssignable(string, Types.instanceType(Integer.class), IrTypeResolver.EMPTY));
	}

	@Test
	void resolvesNearestCommonSuperclassWhenReflectionProvidesTheProof() {
		IrTypeResolver resolver = new ReflectionIrTypeResolver(IrTypeAnalysisTest.class.getClassLoader());
		IrType left = IrType.from(Types.instanceType(java.util.ArrayList.class));
		IrType right = IrType.from(Types.instanceType(java.util.LinkedList.class));

		assertEquals(Types.instanceType(java.util.AbstractList.class),
				IrType.join(left, right, resolver).exactReference());
	}

    @Test
	void propagatesAReferenceJoinThroughAphi() {
        IrBlock block = new IrBlock(0, 10);
        IrBlock left = new IrBlock(1, 1);
        IrBlock right = new IrBlock(2, 2);
        IrPhi phi = new IrPhi(1, block, 0, Types.INT);
        phi.putOperand(left, new IrConstant(2, Types.instanceType(String.class), "a", false));
        phi.putOperand(right, new IrConstant(3, Types.instanceType(String.class), "b", false));
        block.phis().add(phi);

        IrTypeAnalysis.analyze(List.of(block));

        assertEquals(IrTypeKind.REFERENCE, phi.irType().kind());
		assertEquals(Types.instanceType(String.class), phi.type());
	}

	@Test
	void recordsNullRefinementOnTheTakenBranch() {
		IrBlock source = new IrBlock(0, 0);
		IrBlock taken = new IrBlock(1, 10);
		IrBlock fallthrough = new IrBlock(2, 2);
		IrConstant value = new IrConstant(1, Types.OBJECT, null, true);
		Label target = new Label(1, 10);
		source.terminator(new IrTerminator(IrTerminatorKind.IF_ZERO, List.of(value),
				new BranchZeroInstruction(Opcodes.IF_EQZ - Opcodes.IF_EQZ, 0, target)));
		source.addSuccessor(taken, false);
		source.addSuccessor(fallthrough, false);

		IrTypeAnalysis.Result result = IrTypeAnalysis.analyze(new IrMethod(
				new MethodMember("nullFlow", Types.methodTypeFromDescriptor("()V"), 0), 1,
				List.of(source, taken, fallthrough), source, List.of()));

		assertEquals(IrNullability.NULL, result.flowFacts().get(taken).get(value).nullability());
		assertEquals(IrNullability.NOT_NULL, result.flowFacts().get(fallthrough).get(value).nullability());
	}
}
