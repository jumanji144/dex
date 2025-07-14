package me.darknet.dex.file.debug;

import me.darknet.dex.codecs.WriteContext;
import me.darknet.dex.file.DexMapAccess;
import me.darknet.dex.file.items.Item;
import me.darknet.dex.file.items.StringItem;
import me.darknet.dex.file.items.TypeItem;
import me.darknet.dex.codecs.ContextCodec;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.Output;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface DebugInstruction extends Item {

    ContextCodec<DebugInstruction, DexMapAccess, WriteContext> CODEC = new ContextCodec<>() {

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
        public DebugInstruction read(@NotNull Input input, @NotNull DexMapAccess context) throws IOException {
            int opcode = input.readUnsignedByte();
            return switch (opcode) {
                case 0x01 -> new DebugAdvancePc(input.readULeb128());
                case 0x02 -> new DebugAdvanceLine(input.readLeb128());
                case 0x03 -> new DebugStartLocal(input.readULeb128(),
                            getString(context, input.readULeb128p1()),
                            getType(context, input.readULeb128p1()));
                case 0x04 -> new DebugStartLocalExtended(input.readULeb128(),
                            getString(context, input.readULeb128p1()),
                            getType(context, input.readULeb128p1()),
                            getString(context, input.readULeb128p1()));
                case 0x05 -> new DebugEndLocal(input.readULeb128());
                case 0x06 -> new DebugRestartLocal(input.readULeb128());
                case 0x07 -> new DebugSetPrologueEnd();
                case 0x08 -> new DebugSetEpilogueBegin();
                case 0x09 -> new DebugSetFile(getString(context, input.readULeb128p1()));
                default -> new DebugSpecial(opcode);
            };
        }

        @Override
        public void write(DebugInstruction value, @NotNull Output output, @NotNull WriteContext context) throws IOException {
            DexMapAccess index = context.index();
            if(value instanceof DebugAdvancePc advancePc) {
                output.writeByte(0x01);
                output.writeULeb128(advancePc.addrDiff());
            } else if(value instanceof DebugAdvanceLine advanceLine) {
                output.writeByte(0x02);
                output.writeLeb128(advanceLine.lineDiff());
            } else if(value instanceof DebugStartLocal startLocal) {
                output.writeByte(0x03);
                output.writeULeb128(startLocal.registerNum());
                output.writeULeb128p1(index.strings().indexOf(startLocal.name()));
                output.writeULeb128p1(index.types().indexOf(startLocal.type()));
            } else if(value instanceof DebugStartLocalExtended startLocalExtended) {
                output.writeByte(0x04);
                output.writeULeb128(startLocalExtended.registerNum());
                output.writeULeb128p1(index.strings().indexOf(startLocalExtended.name()));
                output.writeULeb128p1(index.types().indexOf(startLocalExtended.type()));
                output.writeULeb128p1(index.strings().indexOf(startLocalExtended.signature()));
            } else if(value instanceof DebugEndLocal endLocal) {
                output.writeByte(0x05);
                output.writeULeb128(endLocal.registerNum());
            } else if(value instanceof DebugRestartLocal restartLocal) {
                output.writeByte(0x06);
                output.writeULeb128(restartLocal.registerNum());
            } else if(value instanceof DebugSetPrologueEnd) {
                output.writeByte(0x07);
            } else if(value instanceof DebugSetEpilogueBegin) {
                output.writeByte(0x08);
            } else if(value instanceof DebugSetFile setFile) {
                output.writeByte(0x09);
                output.writeULeb128p1(index.strings().indexOf(setFile.name()));
            } else if(value instanceof DebugSpecial special) {
                output.writeByte(special.opcode());
            }
        }
    };

}
