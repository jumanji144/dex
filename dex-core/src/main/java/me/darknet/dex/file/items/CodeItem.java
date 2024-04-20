package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.code.EncodedTryCatchHandler;
import me.darknet.dex.file.code.TryItem;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

public record CodeItem(int registers, int in, int out, @Nullable DebugInfoItem debug, List<Format> instructions,
                       int units, List<TryItem> tries, List<EncodedTryCatchHandler> handlers) implements Item {

    public static final ItemCodec<CodeItem> CODEC = new ItemCodec<>() {

        @Override
        public int alignment() {
            return 4;
        }

        @Override
        public CodeItem read0(Input input, DexMapAccess context) throws IOException {
            int registers = input.readUnsignedShort();
            int in = input.readUnsignedShort();
            int out = input.readUnsignedShort();
            int tries = input.readUnsignedShort();
            int debugInfoOffset = input.readInt();

            DebugInfoItem debug = debugInfoOffset == 0 ? null :
                    DebugInfoItem.CODEC.read(input.slice(debugInfoOffset), context);

            int instructionsSize = input.readInt();
            int targetPosition = input.position() + (instructionsSize * 2);
            List<Format> instructions = new ArrayList<>();
            do {
                instructions.add(Format.CODEC.read(input));
            } while (input.position() < targetPosition);

            if (input.position() > targetPosition) {
                throw new IllegalStateException("Read too many instructions");
            } else if (input.position() < targetPosition) {
                throw new IllegalStateException("Read too few instructions");
            }

            List<TryItem> triesItems = new ArrayList<>(tries);
            List<EncodedTryCatchHandler> handlers = new ArrayList<>();
            if (tries != 0) {
                if ((instructionsSize & 1) == 1) {
                    input.readUnsignedShort(); // padding
                }

                Map<Integer, EncodedTryCatchHandler> handlerMap = new HashMap<>();

                int triesPosition = input.position();
                int handlersPosition = triesPosition + tries * (4 + 2 + 2);

                input.position(handlersPosition);

                int handlersSize = input.readULeb128();

                // read handlers first
                for (int i = 0; i < handlersSize; i++) {
                    int handlerPosition = input.position();
                    EncodedTryCatchHandler handler = EncodedTryCatchHandler.CODEC.read(input, context);
                    handlerMap.put(handlerPosition - handlersPosition, handler);
                    handlers.add(handler);
                }

                // store where we ended up
                int endPosition = input.position();

                // now read the tries
                input.position(triesPosition);

                for (int i = 0; i < tries; i++) {
                    int startAddr = input.readInt();
                    int insnCount = input.readUnsignedShort();
                    int handlerOff = input.readUnsignedShort();
                    triesItems.add(new TryItem(startAddr, insnCount, handlerMap.get(handlerOff)));
                }

                // jump back to the end
                input.position(endPosition);
            }

            return new CodeItem(registers, in, out, debug, instructions, instructionsSize, triesItems, handlers);
        }

        @Override
        public void write0(CodeItem value, Output output, WriteContext context) throws IOException {
            output.writeShort(value.registers());
            output.writeShort(value.in());
            output.writeShort(value.out());
            output.writeShort(value.tries().size());
            output.writeInt(value.debug() == null ? 0 : context.offset(value.debug()));

            output.writeInt(value.units());
            for (Format instruction : value.instructions()) {
                Format.CODEC.write(instruction, output);
            }

            if (value.tries().isEmpty()) {
                return;
            }

            if ((value.units() & 1) == 1) {
                output.writeShort(0); // padding
            }

            int triesPosition = output.position();
            int handlersPosition = triesPosition + value.tries().size() * (4 + 2 + 2);

            output.position(handlersPosition);
            output.writeULeb128(value.handlers().size());

            Map<EncodedTryCatchHandler, Integer> handlerOffsets = new HashMap<>();

            for (EncodedTryCatchHandler handler : value.handlers()) {
                handlerOffsets.put(handler, output.position() - handlersPosition);
                EncodedTryCatchHandler.CODEC.write(handler, output, context);
            }

            int endPosition = output.position();

            output.position(triesPosition);

            for (TryItem tryItem : value.tries()) {
                output.writeInt(tryItem.startAddr());
                output.writeShort(tryItem.count());
                output.writeShort(handlerOffsets.get(tryItem.handler()));
            }

            output.position(endPosition);
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CodeItem codeItem = (CodeItem) o;
        return in == codeItem.in && out == codeItem.out && units == codeItem.units && registers == codeItem.registers && Objects.equals(debug, codeItem.debug) && Objects.equals(tries, codeItem.tries) && Objects.equals(instructions, codeItem.instructions) && Objects.equals(handlers, codeItem.handlers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(registers, in, out, debug, instructions, units, tries, handlers);
    }
}
