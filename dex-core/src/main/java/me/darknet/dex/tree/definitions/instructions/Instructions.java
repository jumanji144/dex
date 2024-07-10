package me.darknet.dex.tree.definitions.instructions;

import me.darknet.dex.file.instructions.Opcodes;

import java.util.HashMap;
import java.util.Map;

public class Instructions implements Opcodes {

    public static final Map<Integer, Instruction.InstructionCodec<?, ?>> CODECS = new HashMap<>();

    static {
        register(NopInstruction.CODEC, NOP);
        register(MoveInstruction.CODEC, MOVE, MOVE_FROM16, MOVE_16);
        register(MoveWideInstruction.CODEC, MOVE_WIDE, MOVE_WIDE_FROM16, MOVE_WIDE_16);
        register(MoveObjectInstruction.CODEC, MOVE_OBJECT, MOVE_OBJECT_FROM16, MOVE_OBJECT_16);
        register(MoveResultInstruction.CODEC, MOVE_RESULT, MOVE_RESULT_WIDE, MOVE_RESULT_OBJECT);
        register(MoveExceptionInstruction.CODEC, MOVE_EXCEPTION);
        register(ReturnInstruction.CODEC, RETURN_VOID, RETURN, RETURN_WIDE, RETURN_OBJECT);
        register(ConstInstruction.CODEC, CONST_4, CONST_16, CONST, CONST_HIGH16);
        register(ConstWideInstruction.CODEC, CONST_WIDE_16, CONST_WIDE_32, CONST_WIDE, CONST_WIDE_HIGH16);
        register(ConstStringInstruction.CODEC, CONST_STRING, CONST_STRING_JUMBO);
        register(ConstTypeInstruction.CODEC, CONST_CLASS);
        register(MonitorInstruction.CODEC, MONITOR_ENTER, MONITOR_EXIT);
        register(CheckCastInstruction.CODEC, CHECK_CAST);
        register(InstanceOfInstruction.CODEC, INSTANCE_OF);
        register(ArrayLengthInstruction.CODEC, ARRAY_LENGTH);
        register(NewInstanceInstruction.CODEC, NEW_INSTANCE);
        register(NewArrayInstruction.CODEC, NEW_ARRAY);
        register(FilledNewArrayInstruction.CODEC, FILLED_NEW_ARRAY, FILLED_NEW_ARRAY_RANGE);
        register(FillArrayDataInstruction.CODEC, FILL_ARRAY_DATA);
        register(ThrowInstruction.CODEC, THROW);
        register(GotoInstruction.CODEC, GOTO, GOTO_16, GOTO_32);
        register(PackedSwitchInstruction.CODEC, PACKED_SWITCH);
        register(SparseSwitchInstruction.CODEC, SPARSE_SWITCH);
        register(CompareInstruction.CODEC, CMPL_FLOAT, CMPG_FLOAT, CMPL_DOUBLE, CMPG_DOUBLE, CMP_LONG);
        register(BranchInstruction.CODEC, IF_EQ, IF_NE, IF_LT, IF_GE, IF_GT, IF_LE);
        register(BranchZeroInstruction.CODEC, IF_EQZ, IF_NEZ, IF_LTZ, IF_GEZ, IF_GTZ, IF_LEZ);

        register(ArrayInstruction.CODEC, AGET, AGET_WIDE, AGET_OBJECT, AGET_BOOLEAN, AGET_BYTE, AGET_CHAR, AGET_SHORT,
                APUT, APUT_WIDE, APUT_OBJECT, APUT_BOOLEAN, APUT_BYTE, APUT_CHAR, APUT_SHORT);
        register(StaticFieldInstruction.CODEC, SGET, SGET_WIDE, SGET_OBJECT, SGET_BOOLEAN, SGET_BYTE, SGET_CHAR, SGET_SHORT,
                SPUT, SPUT_WIDE, SPUT_OBJECT, SPUT_BOOLEAN, SPUT_BYTE, SPUT_CHAR, SPUT_SHORT);
        register(InstanceFieldInstruction.CODEC, IGET, IGET_WIDE, IGET_OBJECT, IGET_BOOLEAN, IGET_BYTE, IGET_CHAR, IGET_SHORT,
                IPUT, IPUT_WIDE, IPUT_OBJECT, IPUT_BOOLEAN, IPUT_BYTE, IPUT_CHAR, IPUT_SHORT);
        register(InvokeInstruction.CODEC, INVOKE_VIRTUAL, INVOKE_SUPER, INVOKE_DIRECT, INVOKE_STATIC, INVOKE_INTERFACE);
    }

    private static void register(Instruction.InstructionCodec<?, ?> codec, int... opcodes) {
        for (int opcode : opcodes) {
            CODECS.put(opcode, codec);
        }
    }

}
