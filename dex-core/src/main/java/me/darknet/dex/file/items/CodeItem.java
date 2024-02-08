package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.code.EncodedTryCatchHandler;
import me.darknet.dex.file.code.TryItem;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record CodeItem(int registers, int in, int out, DebugInfoItem debug, List<Format> instructiosn,
                       List<TryItem> tries, List<EncodedTryCatchHandler> handlers) implements Item {

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

            if (input.position() != targetPosition) {
                throw new IllegalStateException("Read too many instructions");
            }

            List<TryItem> triesItems = new ArrayList<>(tries);
            List<EncodedTryCatchHandler> handlers = new ArrayList<>();
            if (tries != 0) {
                if ((instructionsSize & 1) == 1) {
                    input.readUnsignedShort(); // padding
                }

                Map<Integer, EncodedTryCatchHandler> handlerMap = new HashMap<>();

                int triesPosition = input.position();
                int handlersPosition = tries * (4 + 2 + 2) + input.position();

                input.position(handlersPosition);

                int handlersSize = input.readULeb128();

                // read handlers first
                for (int i = 0; i < handlersSize; i++) {
                    EncodedTryCatchHandler handler = EncodedTryCatchHandler.CODEC.read(input, context);
                    handlerMap.put(input.position() - handlersPosition, handler);
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

            return new CodeItem(registers, in, out, debug, instructions, triesItems, handlers);
        }

        @Override
        public void write0(CodeItem value, Output output, DexMapAccess context) throws IOException {
            // TODO
        }
    };

}
