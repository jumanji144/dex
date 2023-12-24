package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.code.EncodedTryCatchHandler;
import me.darknet.dex.file.code.EncodedTypeAddrPair;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record CodeItem(int registers, int in, int out, DebugInfoItem debug, List<TypeItem> tries,
                       List<EncodedTryCatchHandler> handlers) implements Item {

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
            int[] instructions = new int[instructionsSize];
            for (int i = 0; i < instructionsSize; i++) {
                instructions[i] = input.readUnsignedShort();
            }

            List<TypeItem> triesItems = new ArrayList<>();
            List<EncodedTryCatchHandler> handlers = new ArrayList<>();
            if(tries != 0) {
                if (instructionsSize % 2 == 1) {
                    input.readUnsignedShort(); // padding
                }

                for (int i = 0; i < tries; i++) {
                    triesItems.add(TypeItem.CODEC.read(input, context));
                }

                int handlersSize = (int) input.readULeb128();
                for (int i = 0; i < handlersSize; i++) {
                    int catchesSize = (int) input.readLeb128();
                    int length = Math.abs(catchesSize);
                    List<EncodedTypeAddrPair> pairs = new ArrayList<>(length);
                    for (int j = 0; j < length; j++) {
                        int typeIndex = (int) input.readULeb128();
                        int addr = (int) input.readULeb128();
                        pairs.add(new EncodedTypeAddrPair(context.types().get(typeIndex), addr));
                    }
                    int catchAllAddr = catchesSize <= 0 ? (int) input.readULeb128() : 0;
                    handlers.add(new EncodedTryCatchHandler(pairs, catchAllAddr));
                }
            }

            return new CodeItem(registers, in, out, debug, triesItems, handlers);
        }

        @Override
        public void write0(CodeItem value, Output output, DexMapAccess context) throws IOException {
            // TODO
        }
    };

}
