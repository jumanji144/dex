package me.darknet.dex.tree.codec.definition;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.code.EncodedTryCatchHandler;
import me.darknet.dex.file.code.EncodedTypeAddrPair;
import me.darknet.dex.file.code.TryItem;
import me.darknet.dex.file.instructions.*;
import me.darknet.dex.file.items.CodeItem;
import me.darknet.dex.file.items.DebugInfoItem;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.tree.codec.TreeCodec;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.code.Handler;
import me.darknet.dex.tree.definitions.code.TryCatch;
import me.darknet.dex.tree.definitions.instructions.*;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CodeCodec implements TreeCodec<Code, CodeItem> {

    @Override
    public Code map(CodeItem input, DexMap context) {
        Code code = new Code(input.in(), input.out(), input.registers());
        InstructionContext<DexMap> ctx = new InstructionContext<>(input, context,
                new HashMap<>(16),
                new HashMap<>(16), new HashMap<>(16), new HashMap<>(16));
        for (Format instruction : input.instructions()) {
            code.instructions().add(Instruction.CODEC.map(instruction, ctx));
        }
        for (TryItem item : input.tries()) {
            Label start = ctx.label(item.startAddr());
            Label end = ctx.label(item.startAddr() + item.count());

            EncodedTryCatchHandler handler = item.handler();
            List<Handler> handlers = new ArrayList<>();

            for (EncodedTypeAddrPair pair : handler.handlers()) {
                Label handlerStart = ctx.label(pair.addr());
                InstanceType exceptionType = Types.instanceType(pair.exceptionType());
                handlers.add(new Handler(handlerStart, exceptionType));
            }

            if (handler.catchAllAddr() != -1) {
                Label handlerStart = ctx.label(handler.catchAllAddr());
                handlers.add(new Handler(handlerStart, null));
            }

            code.tryCatch().add(new TryCatch(start, end, handlers));
        }
        return code;
    }

    @Override
    public CodeItem unmap(Code output, DexMapBuilder context) {
        List<Format> instructions = new ArrayList<>();

        Map<FormatFilledArrayData, Integer> filledArrayData = new HashMap<>();
        Map<FormatPackedSwitch, Integer> packedSwitches = new HashMap<>();
        Map<FormatSparseSwitch, Integer> sparseSwitches = new HashMap<>();

        InstructionContext<DexMapBuilder> ctx = new InstructionContext<>(null, context,
                new HashMap<>(16), filledArrayData, packedSwitches, sparseSwitches);

        // collect all data and build offsets

        // TODO: account for CONST_STRING_JUMBO
        int position = 0;
        for (Instruction instruction : output.instructions()) {
            // labels will have to be resolved
            if (instruction instanceof Label label) {
                label.offset(position);
                continue;
            }

            position += instruction.byteSize();
        }

        // now we need to create the formats and special data parts
        for (Instruction instruction : output.instructions()) {
            if (instruction instanceof Label) {
                continue;
            }
            switch (instruction) {
                case FillArrayDataInstruction(int array, byte[] data, int elementSize) -> {
                    FormatFilledArrayData filledArray = new FormatFilledArrayData(array, data);
                    filledArrayData.put(filledArray, position);
                    position += filledArray.size();
                }
                case PackedSwitchInstruction(int first, List<Label> targets) -> {
                    int[] targetOffsets = new int[targets.size()];
                    for (int i = 0; i < targets.size(); i++) {
                        targetOffsets[i] = targets.get(i).offset();
                    }
                    FormatPackedSwitch packedSwitch = new FormatPackedSwitch(first, targetOffsets);
                    packedSwitches.put(packedSwitch, position);
                    position += packedSwitch.size();
                }
                case SparseSwitchInstruction(Map<Integer, Label> targets) -> {
                    int[] keys = new int[targets.size()];
                    int[] targetOffsets = new int[targets.size()];
                    int i = 0;
                    for (Map.Entry<Integer, Label> entry : targets.entrySet()) {
                        keys[i] = entry.getKey();
                        targetOffsets[i] = entry.getValue().offset();
                        i++;
                    }
                    FormatSparseSwitch sparseSwitch = new FormatSparseSwitch(keys, targetOffsets);
                    sparseSwitches.put(sparseSwitch, position);
                    position += sparseSwitch.size();
                }
                default -> {}
            }
            instructions.add(Instruction.CODEC.unmap(instruction, ctx));
        }

        DebugInfoItem debugInfo = null;
        List<TryItem> tries = new ArrayList<>();
        List<EncodedTryCatchHandler> handlers = new ArrayList<>();
        for (TryCatch tryCatch : output.tryCatch()) {
            Label start = tryCatch.begin();
            Label end = tryCatch.end();
            List<EncodedTypeAddrPair> handlerPairs = new ArrayList<>();
            int catchAllAddr = -1;
            for (Handler handler : tryCatch.handlers()) {
                int addr = handler.handler().offset();
                if (handler.exceptionType() == null) {
                    catchAllAddr = addr;
                    continue;
                }
                TypeItem exceptionType = context.type(handler.exceptionType());
                handlerPairs.add(new EncodedTypeAddrPair(exceptionType, addr));
            }
            EncodedTryCatchHandler handler = new EncodedTryCatchHandler(handlerPairs, catchAllAddr);
            handlers.add(handler);
            tries.add(new TryItem(start.offset(), end.offset() - start.offset(), handler));
        }
        return new CodeItem(output.registers(), output.in(), output.out(), debugInfo, instructions, List.of(),
                tries, handlers);
    }

}
