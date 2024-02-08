package me.darknet.dex.file.code;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.io.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record EncodedTryCatchHandler(List<EncodedTypeAddrPair> handlers, int catchAllAddr) {

    public static final ContextCodec<EncodedTryCatchHandler, DexMapAccess> CODEC = new ContextCodec<>() {
        @Override
        public EncodedTryCatchHandler read(Input input, DexMapAccess context) throws IOException {
            int catchesSize = input.readLeb128();
            int length = Math.abs(catchesSize);
            List<EncodedTypeAddrPair> pairs = new ArrayList<>(length);
            for (int j = 0; j < length; j++) {
                int typeIndex = input.readULeb128();
                int addr = input.readULeb128();
                pairs.add(new EncodedTypeAddrPair(context.types().get(typeIndex), addr));
            }
            int catchAllAddr = catchesSize <= 0 ? input.readULeb128() : 0;
            return new EncodedTryCatchHandler(pairs, catchAllAddr);
        }

        @Override
        public void write(EncodedTryCatchHandler value, Output output, DexMapAccess context) throws IOException {
            output.writeLeb128(value.handlers().size());
            for (EncodedTypeAddrPair pair : value.handlers()) {
                output.writeULeb128(context.types().indexOf(pair.exceptionType()));
                output.writeULeb128(pair.addr());
            }
            if (value.catchAllAddr() != 0) {
                output.writeULeb128(value.catchAllAddr());
            }
        }
    };

}
