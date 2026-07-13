package me.darknet.dex.convert.ir.lowering;

import me.darknet.dex.convert.ir.*;

import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
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
				values.load(effect.inputs().get(0), effect.inputs().get(0).type());
				values.load(effect.inputs().get(1), me.darknet.dex.tree.type.Types.INT);
				ClassType elementType = effect.inputs().get(2).type();
				values.load(effect.inputs().get(2), elementType);
				mv.visitInsn(me.darknet.dex.convert.ConversionSupport.arrayStoreOpcode(elementType));
			}
			case InstanceFieldInstruction instruction -> {
				values.load(effect.inputs().get(0), instruction.owner());
				values.load(effect.inputs().get(1), instruction.type());
				mv.visitFieldInsn(PUTFIELD, instruction.owner().internalName(), instruction.name(),
						instruction.type().descriptor());
			}
			case StaticFieldInstruction instruction -> {
				values.load(effect.inputs().getFirst(), instruction.type());
				mv.visitFieldInsn(PUTSTATIC, instruction.owner().internalName(), instruction.name(),
						instruction.type().descriptor());
			}
			case FillArrayDataInstruction instruction -> fillArrayData.emit(effect.inputs().get(0), instruction);
			case MonitorInstruction instruction -> {
				values.load(effect.inputs().getFirst(), effect.inputs().getFirst().type());
				mv.visitInsn(instruction.exit() ? MONITOREXIT : MONITORENTER);
			}
			case null -> {
				// No-op effect, nothing to emit.
			}
			default -> throw new IllegalStateException("Unsupported effect payload: " + effect.payload());
		}
	}

	@FunctionalInterface
	interface ValueLoader {
		void load(@NotNull IrValue value, @NotNull ClassType expectedType);
	}

	@FunctionalInterface
	interface FillArrayDataLoader {
		void emit(@NotNull IrValue arrayValue, @NotNull FillArrayDataInstruction instruction);
	}
}

