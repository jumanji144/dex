package me.darknet.dex.file.debug;

import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.Item;
import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.io.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;

import java.io.IOException;

public interface DebugInstruction extends Item {

    ContextCodec<DebugInstruction, DexMapAccess> CODEC = new ContextCodec<>() {

        private StringItem getString(DexMapAccess context, int index) {
            if(index == -1)
                return null;
            return context.strings().get(index);
        }

        private TypeItem getType(DexMapAccess context, int index) {
            if(index == -1)
                return null;
            return context.types().get(index);
        }

        @Override
        public DebugInstruction read(Input input, DexMapAccess context) throws IOException {
            int opcode = input.readUnsignedByte();
            return switch (opcode) {
                case 0x01 -> new DebugAdvancePc((int) input.readULeb128());
                case 0x02 -> new DebugAdvanceLine((int) input.readLeb128());
                case 0x03 -> new DebugStartLocal((int) input.readULeb128(),
                            getString(context, (int) input.readULeb128p1()),
                            getType(context, (int) input.readULeb128p1()));
                case 0x04 -> new DebugStartLocalExtended((int) input.readULeb128(),
                            getString(context, (int) input.readULeb128p1()),
                            getType(context, (int) input.readULeb128p1()),
                            getString(context, (int) input.readULeb128p1()));
                case 0x05 -> new DebugEndLocal((int) input.readULeb128());
                case 0x06 -> new DebugRestartLocal((int) input.readULeb128());
                case 0x07 -> new DebugSetPrologueEnd();
                case 0x08 -> new DebugSetEpilogueBegin();
                case 0x09 -> new DebugSetFile(getString(context, (int) input.readULeb128p1()));
                default -> new DebugSpecial(opcode);
            };
        }

        @Override
        public void write(DebugInstruction value, Output output, DexMapAccess context) throws IOException {

        }

    };

}
