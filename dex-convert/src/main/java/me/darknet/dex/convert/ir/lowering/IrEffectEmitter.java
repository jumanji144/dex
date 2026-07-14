package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;
import me.darknet.dex.convert.ConversionSupport;

import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.MonitorInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import me.darknet.dex.tree.type.ClassType;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Emits IR side effect statements after their input values are materialized.
 */
final class IrEffectEmitter {
	private IrEffectEmitter() {}

	static void emit(@NotNull MethodVisitor mv,
	                 @NotNull IrEffect effect,
	                 @NotNull ValueLoader values,
	                 @NotNull FillArrayDataLoader fillArrayData) {
			switch (effect.payload()) {
			case ArrayInstruction ignored -> {
				values.load(effect.inputs().get(0), inputType(effect, 0));
				values.load(effect.inputs().get(1), inputType(effect, 1));
				ClassType arrayType = inputType(effect, 0);
				ClassType elementType = arrayType instanceof me.darknet.dex.tree.type.ArrayType array
						? array.componentType() : inputType(effect, 2);
				values.load(effect.inputs().get(2), elementType);
				mv.visitInsn(me.darknet.dex.convert.ConversionSupport.arrayStoreOpcode(elementType));
			}
			case InstanceFieldInstruction instruction -> {
				values.load(effect.inputs().get(0), inputType(effect, 0));
				values.load(effect.inputs().get(1), inputType(effect, 1));
				mv.visitFieldInsn(PUTFIELD, instruction.owner().internalName(), instruction.name(),
						instruction.type().descriptor());
			}
			case StaticFieldInstruction instruction -> {
				values.load(effect.inputs().getFirst(), inputType(effect, 0));
				mv.visitFieldInsn(PUTSTATIC, instruction.owner().internalName(), instruction.name(),
						instruction.type().descriptor());
			}
			case FillArrayDataInstruction instruction -> fillArrayData.emit(effect.inputs().get(0),
					inputType(effect, 0), instruction);
			case MonitorInstruction instruction -> {
				values.load(effect.inputs().getFirst(), inputType(effect, 0));
				mv.visitInsn(instruction.exit() ? MONITOREXIT : MONITORENTER);
			}
			case InvokeInstruction instruction -> {
				for (int i = 0; i < effect.inputs().size(); i++)
					values.load(effect.inputs().get(i), inputType(effect, i));
				mv.visitMethodInsn(ConversionSupport.invokeOpcode(instruction.opcode()),
						ConversionSupport.asmOwner(instruction.owner()), instruction.name(),
						instruction.type().descriptor(), instruction.opcode() == Invoke.INTERFACE);
			}
			case InvokeCustomInstruction instruction -> {
				for (int i = 0; i < effect.inputs().size(); i++)
					values.load(effect.inputs().get(i), inputType(effect, i));
				mv.visitInvokeDynamicInsn(instruction.name(), instruction.type().descriptor(),
						ConversionSupport.asmHandle(instruction.handle()),
						ConversionSupport.bootstrapArguments(instruction.arguments()));
			}
			case null -> {
				// No-op effect, nothing to emit.
			}
			default -> throw new IllegalStateException("Unsupported effect payload: " + effect.payload());
		}
	}

	private static @NotNull ClassType inputType(@NotNull IrEffect effect, int index) {
		if (index >= effect.semantics().inputs().size())
			throw new IllegalStateException("Missing semantic input contract " + index + " for " + effect.kind());
		return effect.semantics().inputs().get(index).expected().materializedType();
	}

	@FunctionalInterface
	interface ValueLoader {
		void load(@NotNull IrValue value, @NotNull ClassType expectedType);
	}

	@FunctionalInterface
	interface FillArrayDataLoader {
		void emit(@NotNull IrValue arrayValue, @NotNull ClassType expectedArrayType,
		          @NotNull FillArrayDataInstruction instruction);
	}
}
