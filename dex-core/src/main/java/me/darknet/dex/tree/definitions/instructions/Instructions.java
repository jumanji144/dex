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
        register(UnaryInstruction.CODEC, NEG_INT, NOT_INT, NEG_LONG, NOT_LONG, NEG_FLOAT, NEG_DOUBLE,
                INT_TO_LONG, INT_TO_FLOAT, INT_TO_DOUBLE, LONG_TO_INT, LONG_TO_FLOAT, LONG_TO_DOUBLE,
                FLOAT_TO_INT, FLOAT_TO_LONG, FLOAT_TO_DOUBLE, DOUBLE_TO_INT, DOUBLE_TO_LONG, DOUBLE_TO_FLOAT,
                INT_TO_BYTE, INT_TO_CHAR, INT_TO_SHORT);
        register(BinaryInstruction.CODEC, ADD_INT, SUB_INT, MUL_INT, DIV_INT, REM_INT, AND_INT, OR_INT, XOR_INT, SHL_INT,
                SHR_INT, USHR_INT, ADD_LONG, SUB_LONG, MUL_LONG, DIV_LONG, REM_LONG, AND_LONG, OR_LONG, XOR_LONG,
                SHL_LONG, SHR_LONG, USHR_LONG, ADD_FLOAT, SUB_FLOAT, MUL_FLOAT, DIV_FLOAT, REM_FLOAT, ADD_DOUBLE,
                SUB_DOUBLE, MUL_DOUBLE, DIV_DOUBLE, REM_DOUBLE);
        register(Binary2AddrInstruction.CODEC, ADD_INT_2ADDR, SUB_INT_2ADDR, MUL_INT_2ADDR, DIV_INT_2ADDR, REM_INT_2ADDR,
                AND_INT_2ADDR, OR_INT_2ADDR, XOR_INT_2ADDR, SHL_INT_2ADDR, SHR_INT_2ADDR, USHR_INT_2ADDR,
                ADD_LONG_2ADDR, SUB_LONG_2ADDR, MUL_LONG_2ADDR, DIV_LONG_2ADDR, REM_LONG_2ADDR, AND_LONG_2ADDR,
                OR_LONG_2ADDR, XOR_LONG_2ADDR, SHL_LONG_2ADDR, SHR_LONG_2ADDR, USHR_LONG_2ADDR, ADD_FLOAT_2ADDR,
                SUB_FLOAT_2ADDR, MUL_FLOAT_2ADDR, DIV_FLOAT_2ADDR, REM_FLOAT_2ADDR, ADD_DOUBLE_2ADDR, SUB_DOUBLE_2ADDR,
                MUL_DOUBLE_2ADDR, DIV_DOUBLE_2ADDR, REM_DOUBLE_2ADDR);
        register(BinaryLiteralInstruction.CODEC, ADD_INT_LIT16, RSUB_INT, MUL_INT_LIT16, DIV_INT_LIT16, REM_INT_LIT16,
                AND_INT_LIT16, OR_INT_LIT16, XOR_INT_LIT16, ADD_INT_LIT8, RSUB_INT_LIT8, MUL_INT_LIT8, DIV_INT_LIT8,
                REM_INT_LIT8, AND_INT_LIT8, OR_INT_LIT8, XOR_INT_LIT8, SHL_INT_LIT8, SHR_INT_LIT8, USHR_INT_LIT8);
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
