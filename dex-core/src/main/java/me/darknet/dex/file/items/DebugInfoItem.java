package me.darknet.dex.file.items;

import me.darknet.dex.codecs.ItemCodec;
import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.debug.DebugInstruction;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record DebugInfoItem(int lineStart, List<StringItem> parameterNames, List<DebugInstruction> bytecode) implements Item {

    public static final ItemCodec<DebugInfoItem> CODEC = new ItemCodec<>() {
        @Override
        public DebugInfoItem read0(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int lineStart = input.readULeb128();
            int parametersSize = input.readULeb128();
            List<StringItem> parameterNames = new ArrayList<>(parametersSize);
            for (int i = 0; i < parametersSize; i++) {
                int nameIndex = input.readULeb128p1();
                if(nameIndex == -1) continue;
                parameterNames.add(context.strings().get(nameIndex));
            }
            List<DebugInstruction> bytecode = new ArrayList<>();
            int opcode = input.readUnsignedByte();
            while (opcode != 0) {
                // jump one back
                input.seek(-1);
                bytecode.add(DebugInstruction.CODEC.read(input, context));
                opcode = input.readUnsignedByte();
            }
            return new DebugInfoItem(lineStart, parameterNames, bytecode);
        }

        @Override
        public void write0(DebugInfoItem value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            output.writeULeb128(value.lineStart);
            output.writeULeb128(value.parameterNames.size());
            for (StringItem parameterName : value.parameterNames) {
                output.writeULeb128p1(context.index().strings().indexOf(parameterName));
            }
            for (DebugInstruction instruction : value.bytecode) {
                DebugInstruction.CODEC.write(instruction, output, context);
            }
            output.writeByte(0);
        }
    };

}
