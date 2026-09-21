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
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import me.darknet.dex.tree.definitions.debug.DebugStateMachine;
import me.darknet.dex.tree.definitions.instructions.*;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CodeCodec implements TreeCodec<Code, CodeItem> {
    // TODO: Temporary flag which allows disabling debug info parsing.
    public static boolean readDebug = true;

    /**
     * How many times the instruction layout may be recomputed before it is treated as unsettled. Widths only
     * change when a branch crosses a size boundary, so a handful of passes is far more than convergence needs.
     */
    private static final int MAX_LAYOUT_ATTEMPTS = 8;

    @Override
    public @NotNull Code map(@NotNull CodeItem input, @NotNull DexMap context) {
        Code code = new Code(input.in(), input.out(), input.registers());
        InstructionContext<DexMap> ctx = new InstructionContext<>(input.instructions(), input.offsets(), context,
                new HashMap<>(16), null, null, null);

        List<Instruction> instructions = new ArrayList<>(input.instructions().size());
        List<Integer> instructionOffsets = new ArrayList<>(input.instructions().size());
        int lastInstructionEnd = 0;

        for (int i = 0; i < input.instructions().size(); i++) {
            Format instruction = input.instructions().get(i);

            // Skip pseudo instructions
            if (instruction instanceof PseudoFormat)
                // TODO: These probably need to be tracked in the Code model somehow for round-tripping.
                continue;

            // Add to the instruction list and track offsets for label resolution
            int offset = input.offsets().get(i);
            lastInstructionEnd = offset + instruction.size();
            instructions.add(Instruction.CODEC.map(instruction, ctx));
            instructionOffsets.add(offset);
        }
        for (TryItem item : input.tries()) {
            Label start = ctx.label(item.startAddr());
            Label end = ctx.labelInexact(item.startAddr() + item.count());

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

        // execute debug code
        if (input.debug() != null && readDebug) {
            DebugStateMachine debugStateMachine = new DebugStateMachine();
            DebugInformation debugInfo = debugStateMachine.execute(input.debug(), ctx);
            code.setDebugInfo(debugInfo);
        }

        // add labels into instructions
        List<Instruction> finalInstructions = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            Instruction instruction = instructions.get(i);
            Integer offset = instructionOffsets.get(i);
            if (ctx.labels().containsKey(offset)) {
                finalInstructions.add(ctx.labels().get(offset));
            }
            finalInstructions.add(instruction);
            code.setInstructionOffset(instruction, offset);
        }

        // add a label at the beginning if there isn't one
        if (finalInstructions.isEmpty() || !(finalInstructions.getFirst() instanceof Label)) {
            finalInstructions.addFirst(new Label(0, 0));
        }

        // if there isn't a label at the end, add one
        if (!(finalInstructions.getLast() instanceof Label)) {
            finalInstructions.add(new Label(finalInstructions.size(), lastInstructionEnd));
        }

        code.addInstructions(finalInstructions);

        return code;
    }

    @Override
    public @NotNull CodeItem unmap(@NotNull Code output, @NotNull DexMapBuilder context) {
        List<Instruction> model = output.getInstructions();

        // Instruction widths and their encodings cannot be decided separately: a branch is written in the
        // narrowest form that reaches its target, so its width depends on the distance to that target, and
        // that distance depends on the widths of everything before it. Start from an estimate, encode against
        // it, and re-measure what was actually produced until the two agree.
        int[] widths = new int[model.size()];
        for (int index = 0; index < model.size(); index++) {
            widths[index] = encodedByteSize(model.get(index), context);
        }

        for (int attempt = 0; ; attempt++) {
            Attempt layout = encode(output, context, widths);
            if (layout.settled(widths)) {
                return item(output, context, layout);
            }
            if (attempt >= MAX_LAYOUT_ATTEMPTS) {
                throw new IllegalStateException("Code layout did not settle in " + MAX_LAYOUT_ATTEMPTS
                        + " attempts; instruction widths and branch encodings keep disagreeing");
            }
            widths = layout.widths();
        }
    }

    /**
     * Lays the instructions out at the given widths and encodes each one against those positions.
     *
     * @param output
     * 		Code to encode.
     * @param context
     * 		Pools the instructions reference.
     * @param widths
     * 		Assumed width of each instruction in code units, labels included as zero.
     *
     * @return The encoded instructions together with the widths they actually came to.
     */
    private @NotNull Attempt encode(@NotNull Code output, @NotNull DexMapBuilder context, int @NotNull [] widths) {
        List<Instruction> model = output.getInstructions();

        Map<Integer, Label> labels = new HashMap<>();
        Map<FillArrayDataInstruction, Integer> filledArrayData = new HashMap<>();
        Map<PackedSwitchInstruction, Integer> packedSwitches = new HashMap<>();
        Map<SparseSwitchInstruction, Integer> sparseSwitches = new HashMap<>();

        // collect all data and build offsets

        List<Integer> offsets = new ArrayList<>(model.size());

        int position = 0;
        for (int index = 0; index < model.size(); index++) {
            Instruction instruction = model.get(index);
            // labels will have to be resolved
            if (instruction instanceof Label label) {
                label.position(position);
            }

            offsets.add(position);

            position += widths[index];
        }

        List<Format> instructions = new ArrayList<>(model.size());
        List<Format> extra = new ArrayList<>();
        int[] produced = new int[model.size()];

        InstructionContext<DexMapBuilder> ctx = new InstructionContext<>(model, offsets, context,
                labels, filledArrayData, packedSwitches, sparseSwitches);

        // now we need to create the formats and special data parts
        for (int index = 0; index < model.size(); index++) {
            Instruction instruction = model.get(index);
            if (instruction instanceof Label)
                continue;

            // if the instruction is a special instruction, we need to handle it.
            // arrays and switches must begin on even addresses, so we need to add padding if necessary.
            switch (instruction) {
                case FillArrayDataInstruction insn -> {
                    if ((position & 1) != 0) {
                        extra.add(new Format00op(Opcodes.NOP));
                        position++;
                    }
                    FormatFilledArrayData filledArray = new FormatFilledArrayData(insn.elementSize(), insn.data());
                    filledArrayData.put(insn, position);
                    extra.add(filledArray);
                    position += filledArray.size();
                }
                case PackedSwitchInstruction insn -> {
                    if ((position & 1) != 0) {
                        extra.add(new Format00op(Opcodes.NOP));
                        position++;
                    }
                    int[] targets = new int[insn.targets().size()];
                    for (int i = 0; i < targets.length; i++) {
                        targets[i] = ctx.labelOffset(instruction, insn.targets().get(i));
                    }

                    FormatPackedSwitch packedSwitch = new FormatPackedSwitch(insn.firstKey(), targets);
                    packedSwitches.put(insn, position);
                    extra.add(packedSwitch);
                    position += packedSwitch.size();
                }
                case SparseSwitchInstruction insn -> {
                    if ((position & 1) != 0) {
                        extra.add(new Format00op(Opcodes.NOP));
                        position++;
                    }
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

            Format format = Instruction.CODEC.unmap(instruction, ctx);
            instructions.add(format);
            produced[index] = format.size();
        }

        instructions.addAll(extra);

        return new Attempt(instructions, ctx, produced);
    }

    /**
     * Assembles the code item from a settled layout.
     *
     * @param output
     * 		Code the layout was produced from.
     * @param context
     * 		Pools the code references.
     * @param layout
     * 		Settled layout to write out.
     *
     * @return The encoded code item.
     */
    private @NotNull CodeItem item(@NotNull Code output, @NotNull DexMapBuilder context, @NotNull Attempt layout) {
        DebugInfoItem debugInfo = output.getDebugInfo() == null
                ? null
                : new DebugStateMachine().compile(output.getDebugInfo(), layout.context());
        if (debugInfo != null)
            context.debugInfos().add(debugInfo);

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
        return new CodeItem(output.getRegisters(), output.getIn(), output.getOut(), debugInfo,
                layout.instructions(), List.of(), tries, handlers);
    }

    /**
     * Initial width estimate for one instruction, used only to start the layout off.
     * <p>
     * The estimate cannot be trusted as a final answer: for a branch it reflects the opcode the model
     * happens to carry, while the encoding pass picks the opcode from the settled distance. Whatever this
     * returns is corrected by re-measuring the encoded instructions.
     *
     * @param instruction
     * 		Instruction to estimate.
     * @param context
     * 		Pools the instruction references.
     *
     * @return Estimated width in code units.
     */
    private int encodedByteSize(@NotNull Instruction instruction, @NotNull DexMapBuilder context) {
        if (instruction instanceof ConstStringInstruction constStringInstruction) {
            int index = context.addString(constStringInstruction.string());
            if (constStringInstruction.opcode() == ConstStringInstruction.CONST_STRING_JUMBO || index > 0xffff)
                return 3;
        }
        return instruction.unitSize();
    }

    /**
     * One pass over the instructions, holding the encoded forms and the widths they came to.
     *
     * @param instructions
     * 		Encoded instructions, including any payloads appended after them.
     * @param context
     * 		Positions and labels the pass resolved against, which the final assembly needs for debug info.
     * @param widths
     * 		Width in code units of each instruction in the model, zero for labels.
     */
    private record Attempt(@NotNull List<Format> instructions,
                           @NotNull InstructionContext<DexMapBuilder> context,
                           int @NotNull [] widths) {

        /**
         * @param assumed
         * 		Widths the layout was built from.
         *
         * @return Whether every instruction encoded to the width assumed for it, meaning the layout is fixed.
         */
        boolean settled(int @NotNull [] assumed) {
            return Arrays.equals(widths, assumed);
        }
    }

}
