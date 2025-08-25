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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CodeCodec implements TreeCodec<Code, CodeItem> {

    @Override
    public @NotNull Code map(@NotNull CodeItem input, @NotNull DexMap context) {
        Code code = new Code(input.in(), input.out(), input.registers());
        InstructionContext<DexMap> ctx = new InstructionContext<>(input.instructions(), input.offsets(), context,
                new HashMap<>(16), null, null, null);
        for (Format instruction : input.instructions()) {
            // Skip pseudo instructions
            if (instruction instanceof PseudoFormat)
                // TODO: These probably need to be tracked in the Code model somehow for round-tripping.
                continue;

            code.addInstruction(Instruction.CODEC.map(instruction, ctx));
        }
        for (TryItem item : input.tries()) {
            Label start = ctx.label(item.startAddr());
            int amount = item.count();
            if (amount == 1)
                amount = 0; // single instruction try-catch blocks are inclusive of the end address
            Label end = ctx.labelInexact(item.startAddr() + item.count() - 1);

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

            code.addTryCatch(new TryCatch(start, end, handlers));
        }
        return code;
    }

    @Override
    public @NotNull CodeItem unmap(@NotNull Code output, @NotNull DexMapBuilder context) {
        List<Format> instructions = new ArrayList<>();

        Map<Integer, Label> labels = new HashMap<>();
        Map<FillArrayDataInstruction, Integer> filledArrayData = new HashMap<>();
        Map<PackedSwitchInstruction, Integer> packedSwitches = new HashMap<>();
        Map<SparseSwitchInstruction, Integer> sparseSwitches = new HashMap<>();

        // collect all data and build offsets

        List<Integer> offsets = new ArrayList<>();

        // TODO: account for CONST_STRING_JUMBO
        int position = 0;
        for (Instruction instruction : output.getInstructions()) {
            // labels will have to be resolved
            if (instruction instanceof Label label) {
                label.position(position);
            }

            offsets.add(position);

            position += instruction.byteSize();
        }

        List<Format> extra = new ArrayList<>();

        InstructionContext<DexMapBuilder> ctx = new InstructionContext<>(output.getInstructions(), offsets, context,
                labels, filledArrayData, packedSwitches, sparseSwitches);

        // now we need to create the formats and special data parts
        for (Instruction instruction : output.getInstructions()) {
            if (instruction instanceof Label) {
                continue;
            }
            switch (instruction) {
                case FillArrayDataInstruction insn -> {
                    FormatFilledArrayData filledArray = new FormatFilledArrayData(insn.elementSize(), insn.data());
                    filledArrayData.put(insn, position);
                    extra.add(filledArray);
                    position += filledArray.size();
                }
                case PackedSwitchInstruction insn -> {
                    int[] targets = new int[insn.targets().size()];
                    for (int i = 0; i < targets.length; i++) {
                        targets[i] = ctx.labelOffset(instruction, insn.targets().get(i));
                    }

                    FormatPackedSwitch packedSwitch = new FormatPackedSwitch(insn.first(), targets);
                    packedSwitches.put(insn, position);
                    extra.add(packedSwitch);
                    position += packedSwitch.size();
                }
                case SparseSwitchInstruction insn -> {
                    int[] keys = new int[insn.targets().size()];
                    int[] targetOffsets = new int[insn.targets().size()];
                    int i = 0;
                    for (Map.Entry<Integer, Label> entry : insn.targets().entrySet()) {
                        keys[i] = entry.getKey();
                        targetOffsets[i] = ctx.labelOffset(instruction, entry.getValue());
                        i++;
                    }
                    FormatSparseSwitch sparseSwitch = new FormatSparseSwitch(keys, targetOffsets);
                    sparseSwitches.put(insn, position);
                    extra.add(sparseSwitch);
                    position += sparseSwitch.size();
                }
                default -> {}
            }
            instructions.add(Instruction.CODEC.unmap(instruction, ctx));
        }

        instructions.addAll(extra);

        DebugInfoItem debugInfo = null;
        List<TryItem> tries = new ArrayList<>();
        List<EncodedTryCatchHandler> handlers = new ArrayList<>();
        for (TryCatch tryCatch : output.tryCatch()) {
            Label start = tryCatch.begin();
            Label end = tryCatch.end();
            List<EncodedTypeAddrPair> handlerPairs = new ArrayList<>();
            int catchAllAddr = -1;
            for (Handler handler : tryCatch.handlers()) {
                int addr = handler.handler().position();
                if (handler.exceptionType() == null) {
                    catchAllAddr = addr;
                    continue;
                }
                TypeItem exceptionType = context.type(handler.exceptionType());
                handlerPairs.add(new EncodedTypeAddrPair(exceptionType, addr));
            }
            EncodedTryCatchHandler handler = new EncodedTryCatchHandler(handlerPairs, catchAllAddr);
            handlers.add(handler);
            tries.add(new TryItem(start.position(), end.position() - start.position(), handler));
        }
        return new CodeItem(output.getRegisters(), output.getIn(), output.getOut(), debugInfo, instructions, List.of(),
                tries, handlers);
    }

}
